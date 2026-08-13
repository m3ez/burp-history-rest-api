/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.config;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enumerates bindable local interface addresses without resolving host names. */
public final class NetworkInterfaceCatalog {
    private NetworkInterfaceCatalog() {
    }

    public static List<AddressOption> addresses() {
        Map<String, AddressOption> options = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    if (!networkInterface.isUp()) continue;
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address.isAnyLocalAddress() || address.isMulticastAddress()) continue;
                        String normalized = address.getHostAddress();
                        options.putIfAbsent(normalized, new AddressOption(
                                normalized,
                                networkInterface.getName(),
                                displayName(networkInterface),
                                address.isLoopbackAddress(),
                                address instanceof Inet6Address,
                                true
                        ));
                    }
                }
            }
        } catch (SocketException ignored) {
            // The UI still receives a deterministic loopback fallback below.
        }

        options.putIfAbsent(ApiSettings.DEFAULT_BIND_ADDRESS, new AddressOption(
                ApiSettings.DEFAULT_BIND_ADDRESS,
                "loopback",
                "Loopback",
                true,
                false,
                true
        ));

        List<AddressOption> result = new ArrayList<>(options.values());
        result.sort(Comparator
                .comparing(AddressOption::loopback).reversed()
                .thenComparing(AddressOption::ipv6)
                .thenComparing(AddressOption::interfaceName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AddressOption::address));
        return List.copyOf(result);
    }

    public static String normalizeConfiguredAddress(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.length() > 255
                || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0 || candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Bind address must be a local IPv4 or IPv6 address");
        }

        InetAddress address = resolveNumericAddress(candidate);
        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Use the 'Allow all interfaces' checkbox for wildcard binding");
        }
        if (address.isMulticastAddress()) {
            throw new IllegalArgumentException("A multicast address cannot be used as the REST API bind address");
        }
        return address.getHostAddress();
    }

    public static InetAddress resolveNumericAddress(String value) {
        String candidate = value == null ? "" : value.trim();
        boolean ipv4Literal = candidate.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
        boolean ipv6Literal = candidate.indexOf(':') >= 0;
        if (!ipv4Literal && !ipv6Literal) {
            throw new IllegalArgumentException("Host names are not accepted; select a local IPv4 or IPv6 address");
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (ipv4Literal && !(address instanceof Inet4Address)) {
                throw new IllegalArgumentException("Invalid IPv4 bind address: " + candidate);
            }
            return address;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid bind address: " + candidate, e);
        }
    }

    public static boolean isAssignedLocalAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return true;
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            return networkInterface != null && networkInterface.isUp();
        } catch (SocketException e) {
            return false;
        }
    }

    public static AddressOption savedAddress(String address) {
        InetAddress parsed = resolveNumericAddress(address);
        return new AddressOption(parsed.getHostAddress(), "unavailable", "Saved address",
                parsed.isLoopbackAddress(), parsed instanceof Inet6Address, false);
    }

    private static String displayName(NetworkInterface networkInterface) {
        String displayName = networkInterface.getDisplayName();
        return displayName == null || displayName.isBlank() ? networkInterface.getName() : displayName;
    }

    public record AddressOption(
            String address,
            String interfaceName,
            String displayName,
            boolean loopback,
            boolean ipv6,
            boolean available
    ) {
        @Override
        public String toString() {
            String type = loopback ? "loopback" : ipv6 ? "IPv6" : "IPv4";
            if (!available) return displayName + " — " + address + " [not currently detected]";
            return displayName + " (" + interfaceName + ") — " + address + " [" + type + "]";
        }
    }
}
