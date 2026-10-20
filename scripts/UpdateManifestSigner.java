import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Java source launcher; passwords come only from the environment, never command arguments. */
class UpdateManifestSigner {
    public static void main(String[] args) throws Exception {
        char[] storePassword = Objects.requireNonNull(System.getenv("UPDATE_STORE_PASSWORD"), "UPDATE_STORE_PASSWORD").toCharArray();
        char[] keyPassword = Objects.requireNonNull(System.getenv("UPDATE_KEY_PASSWORD"), "UPDATE_KEY_PASSWORD").toCharArray();
        KeyStore store = KeyStore.getInstance(Path.of(args[1]).toFile(), storePassword);
        var certificate = store.getCertificate(args[2]);
        if (certificate == null) throw new IllegalArgumentException("Signing certificate missing");
        if (args[0].equals("fingerprint")) {
            System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
            return;
        }
        String algorithm = switch (certificate.getPublicKey().getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> throw new IllegalArgumentException("Unsupported signing key");
        };
        byte[] bytes = Files.readAllBytes(Path.of(args[3]));
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign((PrivateKey)store.getKey(args[2], keyPassword));
        signature.update(bytes);
        byte[] signed = signature.sign();
        signature.initVerify(certificate); signature.update(bytes);
        if (!signature.verify(signed)) throw new SecurityException("Manifest signature verification failed");
        Files.writeString(Path.of(args[3] + ".sig"), Base64.getEncoder().encodeToString(signed), java.nio.charset.StandardCharsets.US_ASCII);
        Arrays.fill(storePassword, '\0'); Arrays.fill(keyPassword, '\0');
    }
}
