package hellcat.core.request;

import java.io.FileOutputStream;
import java.io.IOException;

public class HellcatUploadedFile {

    public final String Filename;
    public final String ContentType;
    public final byte[] Data;
    public final int Size;

    public HellcatUploadedFile(String Filename, String ContentType, byte[] Data) {
        this.Filename = Filename;
        this.ContentType = ContentType;
        this.Data = Data;
        this.Size = Data.length;
    }

    public void Save(String DestinationPath) {
        try (FileOutputStream Out = new FileOutputStream(DestinationPath)) {
            Out.write(Data);
        } catch (IOException Err) {
            throw new HellcatRequest.HellcatRequestException(
                "Failed to save uploaded file '" + Filename + "' to '" + DestinationPath + "': " + Err.getMessage()
            );
        }
    }

    @Override
    public String toString() {
        return "<HellcatUploadedFile name=" + Filename + " size=" + Size + ">";
    }
}
