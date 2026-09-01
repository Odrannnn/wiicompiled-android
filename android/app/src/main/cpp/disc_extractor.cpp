#include <jni.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include "nod.h"

namespace {
namespace fs = std::filesystem;

struct HandleDeleter {
    void operator()(NodHandle* handle) const { nod_free(handle); }
};
using NodPtr = std::unique_ptr<NodHandle, HandleDeleter>;

struct FdStream {
    int fd{-1};
    int64_t length{-1};
};

int64_t ReadAt(void* opaque, uint64_t offset, void* output, size_t length) {
    auto* stream = static_cast<FdStream*>(opaque);
    if (!stream || stream->fd < 0 || offset > static_cast<uint64_t>(std::numeric_limits<off_t>::max())) return -1;
    ssize_t result;
    do {
        result = pread(stream->fd, output, length, static_cast<off_t>(offset));
    } while (result < 0 && errno == EINTR);
    return result;
}

int64_t StreamLength(void* opaque) {
    auto* stream = static_cast<FdStream*>(opaque);
    if (!stream || stream->fd < 0) return -1;
    if (stream->length >= 0) return stream->length;
    struct stat info{};
    return fstat(stream->fd, &info) == 0 ? info.st_size : -1;
}

void CloseStream(void* opaque) {
    std::unique_ptr<FdStream> stream(static_cast<FdStream*>(opaque));
    if (stream && stream->fd >= 0) close(stream->fd);
}

std::string LastNodError(const char* operation) {
    const char* detail = nod_error_message();
    return std::string(operation) + ": " + (detail && *detail ? detail : "unknown NOD error");
}

bool SafeSegment(const char* name) {
    if (!name || !*name || !std::strcmp(name, ".") || !std::strcmp(name, "..")) return false;
    for (const unsigned char* cursor = reinterpret_cast<const unsigned char*>(name); *cursor; ++cursor) {
        if (*cursor == '/' || *cursor == '\\' || *cursor < 0x20 || *cursor == 0x7f) return false;
    }
    return true;
}

bool WriteBlob(const fs::path& path, const NodBlob& blob, std::string& error) {
    if (!blob.data && blob.size != 0) { error = "Disc metadata contains an invalid byte range"; return false; }
    std::error_code code;
    fs::create_directories(path.parent_path(), code);
    if (code) { error = "Cannot create " + path.parent_path().string() + ": " + code.message(); return false; }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) { error = "Cannot create " + path.string(); return false; }
    if (blob.size) output.write(reinterpret_cast<const char*>(blob.data), static_cast<std::streamsize>(blob.size));
    output.close();
    if (!output) { error = "Cannot write " + path.string(); return false; }
    return true;
}

struct ProgressContext {
    JNIEnv* env{};
    jobject callback{};
    jmethodID update{};
    uint64_t completed{};
    uint64_t total{};
    uint32_t files{};
    bool cancelled{};
    std::chrono::steady_clock::time_point lastUpdate{};
};

bool Report(ProgressContext& progress, const char* stage, bool force = false) {
    if (!progress.callback || !progress.update) return true;
    auto now = std::chrono::steady_clock::now();
    if (!force && now - progress.lastUpdate < std::chrono::milliseconds(200)) return true;
    progress.lastUpdate = now;
    jstring text = progress.env->NewStringUTF(stage);
    if (!text) return false;
    jboolean keepGoing = progress.env->CallBooleanMethod(progress.callback, progress.update, text,
        static_cast<jlong>(progress.completed), static_cast<jlong>(progress.total));
    progress.env->DeleteLocalRef(text);
    if (progress.env->ExceptionCheck()) return false;
    progress.cancelled = keepGoing != JNI_TRUE;
    return !progress.cancelled;
}

struct CountContext { uint64_t bytes{}; uint32_t files{}; bool overflow{}; };

uint32_t CountEntry(uint32_t index, NodNodeKind kind, const char*, uint32_t size, void* opaque) {
    auto* count = static_cast<CountContext*>(opaque);
    if (kind == NOD_NODE_KIND_FILE) {
        if (count->bytes > std::numeric_limits<uint64_t>::max() - size) count->overflow = true;
        else count->bytes += size;
        count->files++;
    }
    return count->overflow ? NOD_FST_STOP : index + 1;
}

struct DirectoryFrame { uint32_t endIndex; fs::path relative; };
struct ExtractContext {
    NodHandle* partition{};
    fs::path filesRoot;
    std::vector<DirectoryFrame> directories;
    ProgressContext* progress{};
    std::string error;
};

bool ExtractFile(ExtractContext& context, uint32_t index, const fs::path& outputPath, uint32_t expected) {
    NodHandle* raw = nullptr;
    if (nod_partition_open_file(context.partition, index, &raw) != NOD_RESULT_OK) {
        context.error = LastNodError("Cannot open a disc file"); return false;
    }
    NodPtr file(raw);
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!output) { context.error = "Cannot create " + outputPath.string(); return false; }
    std::vector<uint8_t> buffer(1024 * 1024);
    uint64_t written = 0;
    while (written < expected) {
        size_t request = static_cast<size_t>(std::min<uint64_t>(buffer.size(), expected - written));
        int64_t read = nod_read(file.get(), buffer.data(), request);
        if (read < 0) { context.error = LastNodError("Cannot read a disc file"); return false; }
        if (read == 0) { context.error = "Disc file ended before its declared size"; return false; }
        output.write(reinterpret_cast<const char*>(buffer.data()), read);
        if (!output) { context.error = "Cannot write " + outputPath.string(); return false; }
        written += static_cast<uint64_t>(read);
        context.progress->completed += static_cast<uint64_t>(read);
        if (!Report(*context.progress, "Extracting game files")) {
            context.error = context.progress->cancelled ? "Extraction cancelled" : "Progress callback failed";
            return false;
        }
    }
    output.close();
    if (!output) { context.error = "Cannot finish " + outputPath.string(); return false; }
    context.progress->files++;
    return true;
}

uint32_t ExtractEntry(uint32_t index, NodNodeKind kind, const char* name, uint32_t size, void* opaque) {
    auto& context = *static_cast<ExtractContext*>(opaque);
    while (!context.directories.empty() && index >= context.directories.back().endIndex)
        context.directories.pop_back();
    if (!SafeSegment(name)) { context.error = "Disc filesystem contains an unsafe path segment"; return NOD_FST_STOP; }
    fs::path relative = context.directories.empty() ? fs::path(name) : context.directories.back().relative / name;
    fs::path target = context.filesRoot / relative;
    if (kind == NOD_NODE_KIND_DIRECTORY) {
        if (size <= index) { context.error = "Disc filesystem contains an invalid directory range"; return NOD_FST_STOP; }
        std::error_code code;
        fs::create_directories(target, code);
        if (code) { context.error = "Cannot create " + target.string() + ": " + code.message(); return NOD_FST_STOP; }
        context.directories.push_back({size, relative});
    } else if (!ExtractFile(context, index, target, size)) {
        return NOD_FST_STOP;
    }
    return index + 1;
}

const char* FormatName(NodFormat format) {
    switch (format) {
        case NOD_FORMAT_ISO: return "ISO";
        case NOD_FORMAT_CISO: return "CISO";
        case NOD_FORMAT_GCZ: return "GCZ";
        case NOD_FORMAT_NFS: return "NFS";
        case NOD_FORMAT_RVZ: return "RVZ";
        case NOD_FORMAT_WBFS: return "WBFS";
        case NOD_FORMAT_WIA: return "WIA";
        case NOD_FORMAT_TGC: return "TGC";
    }
    return "unknown";
}

void ThrowIOException(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_wiicompiled_portlab_DiscExtractor_nativeExtract(
    JNIEnv* env, jclass, jint sourceFd, jlong sourceLength, jstring destination, jobject callback) {
    if (sourceFd < 0 || !destination) { ThrowIOException(env, "Invalid extraction input"); return nullptr; }
    const char* destinationText = env->GetStringUTFChars(destination, nullptr);
    if (!destinationText) return nullptr;
    fs::path outputRoot(destinationText);
    env->ReleaseStringUTFChars(destination, destinationText);
    try {
        std::error_code code;
        fs::create_directories(outputRoot, code);
        if (code) { ThrowIOException(env, "Cannot create extraction directory: " + code.message()); return nullptr; }
        int duplicate = dup(sourceFd);
        if (duplicate < 0) { ThrowIOException(env, std::string("Cannot duplicate selected document: ") + std::strerror(errno)); return nullptr; }
        auto* fdStream = new FdStream{duplicate, sourceLength};
        NodDiscStream stream{fdStream, ReadAt, StreamLength, CloseStream};
        NodDiscOptions discOptions{};
        discOptions.preloader_threads = 1;
        NodHandle* rawDisc = nullptr;
        if (nod_disc_open_stream(&stream, &discOptions, &rawDisc) != NOD_RESULT_OK) {
            ThrowIOException(env, LastNodError("Cannot open disc image")); return nullptr;
        }
        NodPtr disc(rawDisc);
        NodDiscHeader header{};
        if (nod_disc_header(disc.get(), &header) != NOD_RESULT_OK) {
            ThrowIOException(env, LastNodError("Cannot read disc header")); return nullptr;
        }
        if (!NOD_MAGIC_EQ(header.wii_magic, WII_MAGIC)) {
            ThrowIOException(env, "Selected image is not a Wii disc"); return nullptr;
        }
        if (std::memcmp(header.game_id, "RMCP01", 6) != 0) {
            std::string found(header.game_id, header.game_id + 6);
            ThrowIOException(env, "Expected PAL RMCP01, found " + found); return nullptr;
        }
        if (header.disc_num != 0 || header.disc_version != 0) {
            ThrowIOException(env, "Expected RMCP01 disc 0 revision 0"); return nullptr;
        }
        NodDiscMeta discMeta{};
        if (nod_disc_meta(disc.get(), &discMeta) != NOD_RESULT_OK) {
            ThrowIOException(env, LastNodError("Cannot read disc metadata")); return nullptr;
        }
        NodPartitionOptions partitionOptions{};
        partitionOptions.validate_hashes = true;
        NodHandle* rawPartition = nullptr;
        if (nod_disc_open_partition_kind(disc.get(), NOD_PARTITION_KIND_DATA, &partitionOptions, &rawPartition) != NOD_RESULT_OK) {
            ThrowIOException(env, LastNodError("Cannot open Wii data partition")); return nullptr;
        }
        NodPtr partition(rawPartition);
        NodPartitionMeta partitionMeta{};
        if (nod_partition_meta(partition.get(), &partitionMeta) != NOD_RESULT_OK) {
            ThrowIOException(env, LastNodError("Cannot read partition metadata")); return nullptr;
        }
        CountContext count{};
        nod_partition_iterate_fst(partition.get(), CountEntry, &count);
        if (count.overflow) { ThrowIOException(env, "Disc filesystem size overflow"); return nullptr; }
        uint64_t metadataBytes = partitionMeta.raw_boot.size + partitionMeta.raw_bi2.size
            + partitionMeta.raw_apploader.size + partitionMeta.raw_fst.size + partitionMeta.raw_dol.size
            + partitionMeta.raw_ticket.size + partitionMeta.raw_tmd.size + partitionMeta.raw_cert_chain.size
            + partitionMeta.raw_h3_table.size + 0x100 + 32;
        ProgressContext progress{env, callback};
        if (callback) {
            jclass callbackClass = env->GetObjectClass(callback);
            progress.update = env->GetMethodID(callbackClass, "update", "(Ljava/lang/String;JJ)Z");
            env->DeleteLocalRef(callbackClass);
            if (!progress.update) return nullptr;
        }
        progress.total = count.bytes + metadataBytes;
        progress.lastUpdate = std::chrono::steady_clock::now() - std::chrono::seconds(1);
        if (!Report(progress, "Reading disc metadata", true)) {
            ThrowIOException(env, progress.cancelled ? "Extraction cancelled" : "Progress callback failed"); return nullptr;
        }
        std::string error;
        const std::pair<const char*, NodBlob> metadata[] = {
            {"sys/boot.bin", partitionMeta.raw_boot}, {"sys/bi2.bin", partitionMeta.raw_bi2},
            {"sys/apploader.img", partitionMeta.raw_apploader}, {"sys/fst.bin", partitionMeta.raw_fst},
            {"sys/main.dol", partitionMeta.raw_dol}, {"ticket.bin", partitionMeta.raw_ticket},
            {"tmd.bin", partitionMeta.raw_tmd}, {"cert.bin", partitionMeta.raw_cert_chain},
            {"h3.bin", partitionMeta.raw_h3_table},
        };
        for (const auto& entry : metadata) {
            if (entry.second.data || entry.second.size) {
                if (!WriteBlob(outputRoot / entry.first, entry.second, error)) { ThrowIOException(env, error); return nullptr; }
                progress.completed += entry.second.size;
            }
        }
        NodBlob headerBlob{reinterpret_cast<const uint8_t*>(&header), 0x100};
        if (!WriteBlob(outputRoot / "disc/header.bin", headerBlob, error)) { ThrowIOException(env, error); return nullptr; }
        progress.completed += headerBlob.size;
        uint8_t region[32]{};
        if (nod_seek(disc.get(), 0x4e000, SEEK_SET) < 0) { ThrowIOException(env, LastNodError("Cannot seek to Wii region metadata")); return nullptr; }
        size_t regionRead = 0;
        while (regionRead < sizeof(region)) {
            int64_t read = nod_read(disc.get(), region + regionRead, sizeof(region) - regionRead);
            if (read <= 0) { ThrowIOException(env, LastNodError("Cannot read Wii region metadata")); return nullptr; }
            regionRead += static_cast<size_t>(read);
        }
        if (!WriteBlob(outputRoot / "disc/region.bin", NodBlob{region, sizeof(region)}, error)) { ThrowIOException(env, error); return nullptr; }
        progress.completed += sizeof(region);
        std::error_code filesCode;
        fs::create_directories(outputRoot / "files", filesCode);
        if (filesCode) { ThrowIOException(env, "Cannot create files directory: " + filesCode.message()); return nullptr; }
        ExtractContext extraction{partition.get(), outputRoot / "files", {}, &progress, {}};
        nod_partition_iterate_fst(partition.get(), ExtractEntry, &extraction);
        if (!extraction.error.empty()) { ThrowIOException(env, extraction.error); return nullptr; }
        if (progress.files != count.files) { ThrowIOException(env, "Disc filesystem extraction ended early"); return nullptr; }
        progress.completed = progress.total;
        if (!Report(progress, "Verifying extracted game", true)) {
            ThrowIOException(env, progress.cancelled ? "Extraction cancelled" : "Progress callback failed"); return nullptr;
        }
        std::ostringstream summary;
        summary << "Extracted " << progress.files << " files from " << FormatName(discMeta.format)
                << " (PAL RMCP01 revision 0). Wii partition hashes were checked while reading.";
        return env->NewStringUTF(summary.str().c_str());
    } catch (const std::exception& exception) {
        ThrowIOException(env, std::string("Disc extraction failed: ") + exception.what());
        return nullptr;
    }
}
