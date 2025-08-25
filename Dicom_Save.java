import ij.*;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Dicom_Save implements PlugIn {

    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("No image open");
            return;
        }

        String defaultName = imp.getTitle();
        if (defaultName.contains(".")) {
            defaultName = defaultName.substring(0, defaultName.lastIndexOf("."));
        }
        defaultName += ".dcm";
        
        String defaultDir = IJ.getDirectory("home"); // ホームディレクトリをデフォルトに
        if (imp.getOriginalFileInfo() != null && imp.getOriginalFileInfo().directory != null) {
            defaultDir = imp.getOriginalFileInfo().directory;
        }

        SaveDialog sd = new SaveDialog("Save as Multi-Frame DICOM", defaultDir, defaultName, ".dcm");
        String fileName = sd.getFileName();
        String dir = sd.getDirectory();
        
        if (fileName == null || dir == null) {
            return;
        }

        File outFile = new File(dir, fileName);

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile)))) {
            
            for (int i = 0; i < 128; i++) dos.writeByte(0);
            dos.writeBytes("DICM");

            writeDICOMHeader(dos, imp);

            if (imp.getBitDepth() == 8) {
                writePixelData8bit(dos, imp);
            } else if (imp.getBitDepth() == 16) {
                writePixelData16bit(dos, imp);
            } else if (imp.getBitDepth() == 32) {
                writePixelData32bit(dos, imp);
            } else {
                IJ.error("Unsupported bit depth: " + imp.getBitDepth());
                return;
            }

            IJ.showMessage("DICOM file written to:\n" + outFile.getAbsolutePath());

        } catch (IOException e) {
            IJ.handleException(e);
        }
    }

    private void writeDICOMHeader(DataOutputStream dos, ImagePlus imp) throws IOException {
        writeTag(dos, (short)0x0002, (short)0x0001, "OB", new byte[]{0, 1});
        writeTag(dos, (short)0x0002, (short)0x0002, "UI", "1.2.840.10008.5.1.4.1.1.7"); // Secondary Capture
        writeTag(dos, (short)0x0002, (short)0x0003, "UI", createUID());
        writeTag(dos, (short)0x0002, (short)0x0010, "UI", "1.2.840.10008.1.2.1"); // Explicit VR Little Endian
        writeTag(dos, (short)0x0002, (short)0x0012, "UI", "1.2.3.4.5.6.7.8");
        writeTag(dos, (short)0x0002, (short)0x0013, "SH", "ImageJ_DCM_Writer");
        writeTag(dos, (short)0x0008, (short)0x0060, "CS", "OT");
        writeTag(dos, (short)0x0010, (short)0x0010, "PN", "Patient^Name");
        
        int frames = imp.getStackSize();
        int width = imp.getWidth();
        int height = imp.getHeight();
        int bitDepth = imp.getBitDepth();

        writeTag(dos, (short)0x0028, (short)0x0002, "US", 1);
        writeTag(dos, (short)0x0028, (short)0x0004, "CS", "MONOCHROME2");
        writeTag(dos, (short)0x0028, (short)0x0008, "IS", Integer.toString(frames));
        writeTag(dos, (short)0x0028, (short)0x0010, "US", height);
        writeTag(dos, (short)0x0028, (short)0x0011, "US", width);
        writeTag(dos, (short)0x0028, (short)0x0100, "US", bitDepth);
        writeTag(dos, (short)0x0028, (short)0x0101, "US", bitDepth);
        writeTag(dos, (short)0x0028, (short)0x0102, "US", bitDepth - 1);
        
        int pixelRepresentation = 0;
        if (bitDepth == 16) {
            if (imp.getCalibration().isSigned16Bit()) pixelRepresentation = 1;
        } else if (bitDepth == 32) {
            pixelRepresentation = 1;
        }
        writeTag(dos, (short)0x0028, (short)0x0103, "US", pixelRepresentation);

        long pixelDataLength = (long) frames * width * height * (bitDepth / 8);
        if (pixelDataLength % 2 != 0) pixelDataLength++;

        String pixelDataVR = "OW";
        if (bitDepth == 8) pixelDataVR = "OB";
        else if (bitDepth == 32) pixelDataVR = "OF";
        writeTagHeader(dos, (short) 0x7FE0, (short) 0x0010, pixelDataVR, (int) pixelDataLength);
    }

    private void writeTagHeader(DataOutputStream dos, short group, short element, String vr, int length) throws IOException {
        dos.writeShort(Short.reverseBytes(group));
        dos.writeShort(Short.reverseBytes(element));
        dos.writeBytes(vr);
        if ("OB".equals(vr) || "OW".equals(vr) || "OF".equals(vr) || "SQ".equals(vr) || "UT".equals(vr) || "UN".equals(vr)) {
            dos.writeShort(0);
            dos.writeInt(Integer.reverseBytes(length));
        } else {
            dos.writeShort(Short.reverseBytes((short) length));
        }
    }
    
    private void writeTag(DataOutputStream dos, short group, short element, String vr, Object value) throws IOException {
        byte[] data;
        if (value instanceof String) {
            data = ((String) value).getBytes("ISO-8859-1");
        } else if (value instanceof Integer) {
            data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(((Integer)value).shortValue()).array();
        } else if (value instanceof byte[]) {
            data = (byte[]) value;
        } else {
            throw new IOException("Unsupported value type");
        }
        int length = data.length;
        if (length % 2 != 0) {
            byte[] paddedData = new byte[length + 1];
            System.arraycopy(data, 0, paddedData, 0, length);
            paddedData[length] = 0;
            data = paddedData;
            length++;
        }
        writeTagHeader(dos, group, element, vr, length);
        dos.write(data);
    }

    private void writePixelData8bit(DataOutputStream dos, ImagePlus imp) throws IOException {
        long totalBytes = 0;
        for (int z = 1; z <= imp.getStackSize(); z++) {
            byte[] pixels = (byte[]) imp.getStack().getPixels(z);
            dos.write(pixels);
            totalBytes += pixels.length;
        }
        if (totalBytes % 2 != 0) dos.writeByte(0);
    }

    private void writePixelData16bit(DataOutputStream dos, ImagePlus imp) throws IOException {
        for (int z = 1; z <= imp.getStackSize(); z++) {
            short[] pixels = (short[]) imp.getStack().getPixels(z);
            ByteBuffer byteBuffer = ByteBuffer.allocate(pixels.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            byteBuffer.asShortBuffer().put(pixels);
            dos.write(byteBuffer.array());
        }
    }

    private void writePixelData32bit(DataOutputStream dos, ImagePlus imp) throws IOException {
        for (int z = 1; z <= imp.getStackSize(); z++) {
            float[] pixels = (float[]) imp.getStack().getPixels(z);
            ByteBuffer byteBuffer = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            byteBuffer.asFloatBuffer().put(pixels);
            dos.write(byteBuffer.array());
        }
    }

    private String createUID() {
        return "1.2.826.0.1.3680043.2.1." + System.currentTimeMillis();
    }
}