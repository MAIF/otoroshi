package ssl;

import akka.util.ByteString;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import scala.Option;
import scala.Tuple3;
import scala.Unit;
import scala.Unit$;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;
import utils.RegexPool;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.math.BigInteger;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class X509KeyManagerSnitch extends X509ExtendedKeyManager {

    private X509KeyManager manager;

    public static Cache<String, Tuple3<SSLSession, PrivateKey, X509Certificate[]>> sslSessions = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();

    private Cache<String, Cert> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(20, TimeUnit.SECONDS)
            .build();

    public X509KeyManagerSnitch(X509KeyManager manager) {
        this.manager = manager;
    }

    private void debug(String message) {
        DynamicSSLEngineProvider.logger().underlyingLogger().debug(message);
    }

    private void error(String message, Throwable e) {
        DynamicSSLEngineProvider.logger().underlyingLogger().error(message, e);
    }

    private void info(String message) {
        DynamicSSLEngineProvider.logger().underlyingLogger().info(message);
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

    public String chooseEngineClientAlias(String[] s, Principal[] p, SSLEngine ssl) {
        debug("X509KeyManagerSnitch.chooseEngineClientAlias(" + s + ")");
        return this.chooseClientAlias(s, p, (Socket) null);
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
        if (s.startsWith("tmp-gen-")) {
            Cert cert = cache.getIfPresent(s);
            if (cert != null) {
                return cert.certificatesChain();
            } else {
                return manager.getCertificateChain(s.replace("tmp-gen-", ""));
            }
        } else {
            return manager.getCertificateChain(s);
        }
    }

    @Override
    public PrivateKey getPrivateKey(String s) {
        debug("X509KeyManagerSnitch.getPrivateKey(" + s + ")");
        if (s.startsWith("tmp-gen-")) {
            Cert cert = cache.getIfPresent(s);
            if (cert != null) {
                return cert.cryptoKeyPair().getPrivate();
            } else {
                return manager.getPrivateKey(s.replace("tmp-gen-", ""));
            }
        } else {
            return manager.getPrivateKey(s);
        }
    }

    public String chooseEngineServerAlias(String s, Principal[] p, SSLEngine ssl) {
        Option<String> sessionKey = SSLSessionJavaHelper.computeKey(ssl.getHandshakeSession());
        try {
            String host = ssl.getPeerHost();
            if (host != null) {
                String key = "tmp-gen-" + host;
                String[] aliases = manager.getServerAliases(s, p);
                debug("host: " + host + ", aliases: " + (aliases != null ? aliases.length : 0));
                if (host != null && aliases != null) {
                    List<String> al = Arrays.asList(aliases);
                    Optional<String> theFirst = al.stream().findFirst();
                    Optional<String> theFirstMatching = al.stream().filter(alias -> RegexPool.apply(alias).matches(host)).findFirst();
                    if (theFirstMatching.isPresent()) {
                        String first = theFirstMatching.orElse(theFirst.get());
                        debug("chooseEngineServerAlias: " + host + " - " + theFirst + " - " + first);
                        sessionKey.foreach(skey -> {
                            info("putting 1 " + skey);
                            sslSessions.put(skey, Tuple3.apply(ssl.getSession(), manager.getPrivateKey(first), manager.getCertificateChain(first)));
                            return Unit$.MODULE$;
                        });
                        return first;
                    } else {
                        if (cache.getIfPresent(key) != null) {
                            return key;
                        } else {
                            env.Env env = DynamicSSLEngineProvider.getCurrentEnv();
                            if (env != null) {
                                Option<Cert> certOpt = env.datastores().certificatesDataStore().jautoGenerateCertificateForDomain(host, env);
                                if (certOpt.isDefined()) {
                                    Cert cert = certOpt.get();
                                    cache.put(key, cert);
                                    if (!cert.subject().contains("CN=NotAllowedCert")) { // TODO: replace
                                        DynamicSSLEngineProvider.addCertificates(certOpt.toList(), env);
                                    }
                                    sessionKey.foreach(skey -> {
                                        info("putting 2 " + skey);
                                        sslSessions.put(skey, Tuple3.apply(ssl.getSession(), cert.cryptoKeyPair().getPrivate(), cert.certificatesChain()));
                                        return Unit$.MODULE$;
                                    });
                                    return key;
                                } else {
                                    throw new NoCertificateFoundException(host);
                                }
                            } else {
                                throw new NoCertificateFoundException(host);
                            }
                        }
                    }
                } else {
                    Cert c = cache.getIfPresent(key);
                    if (c != null) {
                        sessionKey.foreach(skey -> {
                            info("putting 3 " + skey);
                            sslSessions.put(skey, Tuple3.apply(ssl.getSession(), c.cryptoKeyPair().getPrivate(), c.certificatesChain()));
                            return Unit$.MODULE$;
                        });
                        return key;
                    } else if (host == null) {
                        throw new NoHostnameFoundException();
                    } else if (aliases == null) {
                        throw new NoAliasesFoundException();
                    } else {
                        throw new NoHostFoundException();
                    }
                }
            } else {
                throw new NoHostnameFoundException();
            }
        } catch (Exception e) {
            error("Error while chosing server alias", e);
            return "--";
        }
    }
}
