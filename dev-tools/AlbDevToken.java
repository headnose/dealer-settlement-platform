import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * LOCAL DEV ONLY. Mints an x-amzn-oidc-data token the way the ALB does (ES256, kid + signer headers),
 * signed with the dev private key in .local-keys/ (never committed).
 * KEY_ID and SIGNER must match security.alb.key-id / security.alb.signer in the services' application.yaml.
 *
 * Usage (from the project root):
 *   java dev-tools/AlbDevToken.java <subject> <groups, comma-separated, or - for none> [minutes, default 60]
 */
public class AlbDevToken {

    private static final Path PRIVATE_KEY = Path.of(".local-keys/alb-dev-private.pem");
    private static final String KEY_ID = "local-dev-1";
    private static final String SIGNER =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/dsp-local/0000000000000000";
    /** Only plain characters, so the hand-built JSON needs no escaping. */
    private static final String SAFE = "[A-Za-z0-9._@-]+";

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: java dev-tools/AlbDevToken.java <subject> <groups|-> [minutes]");
            System.exit(1);
        }
        String subject = requireSafe(args[0]);
        String groups = args[1].equals("-") ? "" : Arrays.stream(args[1].split(","))
                .map(AlbDevToken::requireSafe)
                .map(group -> "\"" + group + "\"")
                .collect(Collectors.joining(","));
        long minutes = args.length == 3 ? Long.parseLong(args[2]) : 60;
        long expiresAt = Instant.now().plusSeconds(minutes * 60).getEpochSecond();

        String header = "{\"alg\":\"ES256\",\"kid\":\"" + KEY_ID + "\",\"signer\":\"" + SIGNER + "\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + subject + "\",\"groups\":[" + groups + "],\"exp\":" + expiresAt + "}";
        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(payload.getBytes(StandardCharsets.UTF_8));

        // JWS ES256 wants the raw 64-byte R||S signature (IEEE P1363), not DER: hence this algorithm name.
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        System.out.println(signingInput + "." + base64Url(signature.sign()));
    }

    private static PrivateKey privateKey() throws Exception {
        String base64 = Files.readString(PRIVATE_KEY)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String requireSafe(String value) {
        if (!value.matches(SAFE)) {
            throw new IllegalArgumentException("Only letters, digits and . _ @ - allowed: " + value);
        }
        return value;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}