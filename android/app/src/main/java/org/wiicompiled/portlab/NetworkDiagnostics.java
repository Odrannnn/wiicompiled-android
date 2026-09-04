package org.wiicompiled.portlab;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Read-only checks for the Android network path used before Retro-WFC login. */
final class NetworkDiagnostics {
    private static final String RETRO_WFC_HOST = "play.rwfc.net";

    static String run(Context context) {
        StringBuilder report = new StringBuilder("\n\nRetro-WFC network path:\n");
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities == null) {
                report.append("FAIL: Android has no active network.");
                return report.toString();
            }
            report.append("Android Internet capability: ")
                .append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "yes" : "no")
                .append("; validated: ")
                .append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "yes" : "no")
                .append("; transport: ").append(transport(capabilities)).append('\n');

            InetAddress[] addresses = InetAddress.getAllByName(RETRO_WFC_HOST);
            if (addresses.length == 0) throw new java.net.UnknownHostException(RETRO_WFC_HOST);
            report.append("DNS ").append(RETRO_WFC_HOST).append(": ");
            for (int index = 0; index < addresses.length; index++) {
                if (index > 0) report.append(", ");
                report.append(addresses[index].getHostAddress());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(addresses[0], 80), 5_000);
                report.append("\nPASS: Retro-WFC login endpoint accepts a TCP connection on port 80.");
            }
        } catch (Exception error) {
            report.append("FAIL: ").append(error.getClass().getSimpleName());
            if (error.getMessage() != null && !error.getMessage().isBlank())
                report.append(": ").append(error.getMessage());
        }
        report.append("\nThis tests Android connectivity, DNS, and TCP only; it does not authenticate a game account.");
        return report.toString();
    }

    private static String transport(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        return "other";
    }
}
