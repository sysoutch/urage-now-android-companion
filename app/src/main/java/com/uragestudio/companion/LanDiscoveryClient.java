package com.uragestudio.companion;

import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LanDiscoveryClient {
    private static final int PORT = 47820;
    private static final byte[] PROBE = "URAGE_STUDIO_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
    private final WifiManager wifiManager;

    public LanDiscoveryClient(WifiManager wifiManager) {
        this.wifiManager = wifiManager;
    }

    public List<DashboardInstance> discover(int durationMs) throws Exception {
        WifiManager.MulticastLock lock = wifiManager.createMulticastLock("urage-companion-discovery");
        lock.setReferenceCounted(false);
        lock.acquire();
        Map<String, DashboardInstance> instances = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + durationMs;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(300);
            for (InetAddress address : broadcastAddresses()) {
                socket.send(new DatagramPacket(PROBE, PROBE.length, address, PORT));
            }
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[2048];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                    JSONObject json = new JSONObject(new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8));
                    int port = json.optInt("port", 4782);
                    String advertised = json.optString("baseUrl", "").replaceAll("/+$", "");
                    String baseUrl = advertised.startsWith("http://") || advertised.startsWith("https://")
                        ? advertised
                        : "http://" + response.getAddress().getHostAddress() + ":" + port;
                    instances.put(baseUrl, new DashboardInstance(
                        json.optString("name", "URage NOW"),
                        baseUrl,
                        json.optString("certificateSha256", "")
                    ));
                } catch (SocketTimeoutException ignored) {
                    // Keep collecting replies until the bounded discovery window closes.
                }
            }
        } finally {
            if (lock.isHeld()) lock.release();
        }
        return new ArrayList<>(instances.values());
    }

    private List<InetAddress> broadcastAddresses() throws Exception {
        LinkedHashSet<InetAddress> addresses = new LinkedHashSet<>();
        addresses.add(InetAddress.getByName("255.255.255.255"));
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            addresses.add(InetAddress.getByAddress(new byte[]{
                (byte) (broadcast & 0xff),
                (byte) ((broadcast >> 8) & 0xff),
                (byte) ((broadcast >> 16) & 0xff),
                (byte) ((broadcast >> 24) & 0xff)
            }));
        }
        return new ArrayList<>(addresses);
    }
}
