package ssl;

import utils.RegexPool;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class X509KeyManagerSnitch extends X509ExtendedKeyManager {

    private X509KeyManager manager;

    public X509KeyManagerSnitch(X509KeyManager manager) {
        this.manager = manager;
    }

    private void debug(String message) {
        DynamicSSLEngineProvider.logger().underlyingLogger().debug(message);
    }

    @Override
    public String[] getClientAliases(String s, Principal[] p) {
        debug("X509KeyManagerSnitch.getClientAliases(" + s + ")");
        return manager.getClientAliases(s, p);
    }

    @Override
    public String chooseClientAlias(String[] s, Principal[] p, Socket so) {
        debug("X509KeyManagerSnitch.chooseClientAlias(" + s + ")");
        return manager.chooseClientAlias(s, p, so);
    }

    @Override
    public String[] getServerAliases(String s, Principal[] p) {
        debug("X509KeyManagerSnitch.getServerAliases(" + s + ")");
        return manager.getServerAliases(s, p);
    }

    @Override
    public String chooseServerAlias(String s, Principal[] p, Socket so) {
        debug("X509KeyManagerSnitch.chooseServerAlias(" + s + ")");
        return manager.chooseServerAlias(s, p, so);
    }

    @Override
    public X509Certificate[] getCertificateChain(String s) {
        debug("X509KeyManagerSnitch.getCertificateChain(" + s + ")");
        return manager.getCertificateChain(s);
    }

    @Override
    public PrivateKey getPrivateKey(String s) {
        debug("X509KeyManagerSnitch.getPrivateKey(" + s + ")");
        return manager.getPrivateKey(s);
    }

    public String chooseEngineClientAlias(String[] s, Principal[] p, SSLEngine ssl) {
        debug("X509KeyManagerSnitch.chooseEngineClientAlias(" + s + ")");
        return this.chooseClientAlias(s, p, (Socket) null);
    }

    public String chooseEngineServerAlias(String s, Principal[] p, SSLEngine ssl) {
        debug("X509KeyManagerSnitch.chooseEngineServerAlias(" + s + ")");
        try {
            String host = ssl.getPeerHost();
            String[] aliases = manager.getServerAliases(s, p);
            if (aliases != null) {
                List<String> al = Arrays.asList(aliases);
                Optional<String> theFirst = al.stream().findFirst();
                String first = al.stream().filter(alias -> RegexPool.apply(host).matches(alias)).findFirst().orElse(theFirst.get());
                return first;
            } else {
                return this.chooseServerAlias(s, p, (Socket) null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return this.chooseServerAlias(s, p, (Socket) null);
        }
    }
}
