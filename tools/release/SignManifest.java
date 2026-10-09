import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;

/** Signs exact JSON bytes with the SAME explicitly supplied key used to sign the APK. */
class SignManifest {
    public static void main(String[] args) {
        char[] storePassword = null, keyPassword = null;
        try {
            if (args.length != 3) throw new IllegalArgumentException();
            storePassword = required("ROBOTOS_STORE_PASSWORD").toCharArray();
            keyPassword = required("ROBOTOS_KEY_PASSWORD").toCharArray();
            KeyStore store = KeyStore.getInstance(new java.io.File(required("ROBOTOS_KEYSTORE_PATH")), storePassword);
            String alias = required("ROBOTOS_KEY_ALIAS");
            Certificate certificate = store.getCertificate(alias);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!digest.equals(required("ROBOTOS_SIGNER_SHA256"))) throw new GeneralSecurityException();
            PrivateKey key = (PrivateKey) store.getKey(alias, keyPassword);
            String algorithm = switch (key.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> throw new GeneralSecurityException();
            };
            byte[] payload = Files.readAllBytes(Path.of(args[0]));
            if (payload.length == 0 || payload.length > 8192) throw new IllegalArgumentException();
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(payload);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new GeneralSecurityException();
            var base64 = Base64.getEncoder();
            Files.writeString(Path.of(args[1]), "{\"payload\":\"" + base64.encodeToString(payload)
                + "\",\"signature\":\"" + base64.encodeToString(signature) + "\"}\n");
            Files.write(Path.of(args[2]), certificate.getEncoded()); // public certificate only
        } catch (Exception error) {
            System.err.println("Release metadata signing failed; no release should be published.");
            System.exit(1);
        } finally {
            if (storePassword != null) Arrays.fill(storePassword, '\0');
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
}
