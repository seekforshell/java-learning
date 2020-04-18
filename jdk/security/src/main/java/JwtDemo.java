import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Base64;

/**
 * Description:
 *
 * @author: renfei
 * Version: 1.0
 * Create Date Time: 2020-03-24 10:24.
 */

public class JwtDemo {
    public static void main(String args[]) throws UnrecoverableKeyException, CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException {


        PrivateKey privateKey = getPrivateKey("gateway.jks", "admin", "jwt");
        System.out.println("私钥：" + privateKey.getEncoded());
        System.out.println("私钥格式：" + privateKey.getFormat());
        PublicKey publicKey = getPublicKey("gateway.jks", "admin", "jwt");
        System.out.println("公钥：" + publicKey.toString());

    }

    private static PrivateKey getPrivateKey(String fileName, String password, String alias) throws KeyStoreException,
            IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(inputStream, "123456".toCharArray());

        return (PrivateKey) keyStore.getKey("jwt", "123456".toCharArray());

    }

    private static PublicKey getPublicKey(String fileName, String password, String alias) throws KeyStoreException,
            IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(inputStream, "123456".toCharArray());

        return keyStore.getCertificate("jwt").getPublicKey();

    }
}
