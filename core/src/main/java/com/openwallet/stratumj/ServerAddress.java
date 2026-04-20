package com.openwallet.stratumj;

/**
 * @author John L. Jegutanis
 */
final public class ServerAddress {
    final private String host;
    final private int port;
    final private boolean useTls;

    public ServerAddress(String host, int port) {
        this(host, port, false);
    }

    public ServerAddress(String host, int port, boolean useTls) {
        this.host = host;
        this.port = port;
        this.useTls = useTls;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isUseTls() {
        return useTls;
    }

    @Override
    public String toString() {
        return "ServerAddress{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", useTls=" + useTls +
                '}';
    }
}
