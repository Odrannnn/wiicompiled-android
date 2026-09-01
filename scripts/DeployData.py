import argparse, os, socket, struct, sys, time
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, required=True)
parser.add_argument('--token', required=True)
parser.add_argument('mappings', nargs='+', help='local_path=remote_relative_path')
args = parser.parse_args()
entries = []
for mapping in args.mappings:
    local, remote = mapping.split('=', 1)
    local = os.path.abspath(local)
    if os.path.isdir(local):
        for base, _, names in os.walk(local):
            for name in names:
                path = os.path.join(base, name)
                relative = os.path.relpath(path, local).replace(os.sep, '/')
                entries.append((path, remote.rstrip('/') + '/' + relative))
    else: entries.append((local, remote))
sock = None
for _ in range(100):
    try:
        sock = socket.create_connection(('127.0.0.1', args.port), timeout=5); break
    except OSError: time.sleep(.1)
if sock is None: raise SystemExit('Importer did not open its ADB socket')
with sock, sock.makefile('wb', buffering=1024*1024) as output:
    token = args.token.encode(); output.write(struct.pack('!I', len(token))); output.write(token)
    total = 0
    for index, (local, remote) in enumerate(entries, 1):
        name = remote.encode('utf-8'); size = os.path.getsize(local)
        output.write(struct.pack('!Iq', len(name), size)); output.write(name)
        with open(local, 'rb') as source:
            while chunk := source.read(1024*1024): output.write(chunk)
        total += size
        if index % 64 == 0: print(f'{index}/{len(entries)} files, {total//1048576} MiB', flush=True)
    output.write(struct.pack('!Iq', 0, 0)); output.flush()
print(f'Complete: {len(entries)} files, {total//1048576} MiB')
