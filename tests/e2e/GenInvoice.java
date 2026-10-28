import com.aegisx.integrity.support.EvidenceFixtures;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a real invoice PDF using the project's own fixture generator. Args: invoiceNo orderRef currency totalMajor outPath */
public class GenInvoice {
    public static void main(String[] args) throws Exception {
        byte[] pdf = EvidenceFixtures.invoicePdf(args[0], args[1], args[2], args[3]);
        Files.write(Path.of(args[4]), pdf);
        System.out.println("wrote " + pdf.length + " bytes to " + args[4]);
    }
}
