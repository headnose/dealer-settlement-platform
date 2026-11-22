package io.github.headnose.dsp.notification;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Puts statements where PWT would (D24) and digs parts out of multipart emails. */
final class StorageTestSupport {

    static final String BUCKET = "wire-statements";

    private StorageTestSupport() {
    }

    /** Uploads a statement for this submission, like PWT does before it publishes (D23). */
    static String putStatement(S3Client s3Client, String submissionId, String content) {
        s3Client.putObject(request -> request
                        .bucket(BUCKET)
                        .key(submissionId + "/LN-1001.xml")
                        .contentType("application/xml; charset=UTF-8"),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
        return KafkaTestSupport.attachmentPath(submissionId);
    }

    /** Depth-first search through nested multiparts (MimeMessageHelper nests body and attachments). */
    static Part findPart(Part part, String fileName) throws Exception {
        if (fileName.equals(part.getFileName())) {
            return part;
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                Part found = findPart(child, fileName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The first text/plain part that is not an attachment. */
    static String plainText(Part part) throws Exception {
        if (part.isMimeType("text/plain") && part.getFileName() == null) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = plainText(multipart.getBodyPart(i));
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }
}