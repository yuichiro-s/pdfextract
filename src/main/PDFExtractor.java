import javafx.beans.binding.ObjectExpression;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.pagenavigation.PDThreadBead;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class PDFExtractor extends PDFGraphicsStreamEngine {

    static boolean useText = false;
    static boolean useDraw = false;
    static boolean useImage = false;
    static boolean useFontName = false;
    static boolean useBounding = false;
    static boolean useGlyph = false;

    public static void main(String[] args) throws IOException {
        Path path = Paths.get(args[0]);
        for (String arg : args) {
            if (arg.equals("-text")) useText = true;
            else if (arg.equals("-draw")) useDraw = true;
            else if (arg.equals("-image")) useImage = true;
            else if (arg.equals("-fontName")) useFontName = true;
            else if (arg.equals("-bounding")) useBounding = true;
            else if (arg.equals("-glyph")) useGlyph = true;
        }
        if (!useText && !useDraw && !useImage) useText = useDraw = useImage = true;

        if (Files.isDirectory(path)) {
            FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".pdf")) {
                        String outPath = file.toString() + "txt";
                        System.out.println(file.toFile());
                        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outPath), "UTF-8"))) {
                            processFile(file, w);
                        }
                        catch (Exception e) { }
                    }
                    return FileVisitResult.CONTINUE;
                }
            };
            Files.walkFileTree(path, visitor);
        }
        else {
            try (Writer w = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"))) {
                processFile(path, w);
            }
            catch (Exception e) { }
        }
    }

    static void processFile(Path path, Writer w) throws IOException {
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDFExtractor ext = new PDFExtractor(doc.getPage(i), i + 1, w);
                ext.processPage(doc.getPage(i));
                ext.write();
            }
        }
    }

    Writer output;
    int pageIndex;
    int pageRotation;
    PDRectangle pageSize;
    Matrix translateMatrix;
    final GlyphList glyphList;
    List<Image> imageBuffer;
    List<Object> buffer = new ArrayList<>();

    AffineTransform flipAT;
    AffineTransform rotateAT;
    AffineTransform transAT;

    public PDFExtractor(PDPage page, int pageIndex, Writer output) throws IOException {
        super(page);
        this.pageIndex = pageIndex;
        this.output = output;

        String path = "org/apache/pdfbox/resources/glyphlist/additional.txt";
        InputStream input = GlyphList.class.getClassLoader().getResourceAsStream(path);
        this.glyphList = new GlyphList(GlyphList.getAdobeGlyphList(), input);

        this.pageRotation = page.getRotation();
        this.pageSize = page.getCropBox();
        if (this.pageSize.getLowerLeftX() == 0.0F && this.pageSize.getLowerLeftY() == 0.0F) {
            this.translateMatrix = null;
        } else {
            this.translateMatrix = Matrix.getTranslateInstance(-this.pageSize.getLowerLeftX(), -this.pageSize.getLowerLeftY());
        }

        // taken from DrawPrintTextLocations for setting flipAT, rotateAT and transAT
        PDRectangle cropBox = page.getCropBox();
        // flip y-axis
        flipAT = new AffineTransform();
        flipAT.translate(0, page.getBBox().getHeight());
        flipAT.scale(1, -1);

        // page may be rotated
        rotateAT = new AffineTransform();
        int rotation = page.getRotation();
        if (rotation != 0) {
            PDRectangle mediaBox = page.getMediaBox();
            switch (rotation) {
                case 90:
                    rotateAT.translate(mediaBox.getHeight(), 0);
                    break;
                case 270:
                    rotateAT.translate(0, mediaBox.getWidth());
                    break;
                case 180:
                    rotateAT.translate(mediaBox.getWidth(), mediaBox.getHeight());
                    break;
                default:
                    break;
            }
            rotateAT.rotate(Math.toRadians(rotation));
        }
        // cropbox
        transAT = AffineTransform.getTranslateInstance(-cropBox.getLowerLeftX(), cropBox.getLowerLeftY());

        ImageExtractor ext = new ImageExtractor();
        ext.processPage(page);
        imageBuffer = ext.buffer;
    }

    float getPageHeight() { return getPage().getCropBox().getHeight(); }

    void addDraw(String op, float... values) {
        if (useDraw) buffer.add(new Draw(op, values));
    }

    void writeText(List<Text> textBuffer) throws IOException {
        float averageW = 0;
        for (Text t : textBuffer) averageW += t.bw;
        averageW /= textBuffer.size();

        Text prev = textBuffer.get(0);
        for (Text curr : textBuffer) {
            float expectedX = prev.bx + prev.bw + averageW * 0.3f;
            if (curr.bx > expectedX) output.write("\n");
            output.write(String.valueOf(pageIndex));
            output.write("\tTEXT");
            output.write("\t" + curr.unicode);
            if (useBounding) {
                output.write("\t" + String.valueOf(curr.bx));
                output.write("\t" + String.valueOf(curr.by));
                output.write("\t" + String.valueOf(curr.bw));
                output.write("\t" + String.valueOf(curr.bh));
            }
            if (useGlyph) {
                output.write("\t" + String.valueOf(curr.gx));
                output.write("\t" + String.valueOf(curr.gy));
                output.write("\t" + String.valueOf(curr.gw));
                output.write("\t" + String.valueOf(curr.gh));
            }
            if (useFontName) output.write("\t" + curr.font.getName());
            output.write("\n");

            prev = curr;
        }
        output.write("\n");
    }

    void writeDraw(List<Draw> drawBuffer) throws IOException {
        for (Draw d : drawBuffer) {
            output.write(String.valueOf(pageIndex));
            output.write("\tDRAW");
            output.write("\t" + String.valueOf(d.op));
            for (Float f : d.values)  output.write("\t" + String.valueOf(f));
            output.write("\n");
        }
        output.write("\n");
    }

    void write() throws IOException {
        int i = 0;
        while (i < buffer.size()) {
            Object obj = buffer.get(i);
            if (obj instanceof Text) {
                Text t0 = (Text)obj;
                List<Text> textBuffer = new ArrayList<>();
                while (i < buffer.size()) {
                    obj = buffer.get(i);
                    if (obj instanceof Text == false) break;
                    Text t = (Text)obj;
                    if (t.by != t0.by || t.bh != t0.bh) break;
                    textBuffer.add(t);
                    i++;
                }
                writeText(textBuffer);
            }
            else if (obj instanceof Draw) {
                List<Draw> drawBuffer = new ArrayList<>();
                while (i < buffer.size()) {
                    obj = buffer.get(i);
                    if (obj instanceof Draw == false) break;
                    Draw d = (Draw)obj;
                    drawBuffer.add(d);
                    i++;
                    if (d.op.endsWith("_PATH")) {
                        writeDraw(drawBuffer);
                        break;
                    }
                }
            }
            else if (obj instanceof Image) {
                Image image = (Image)obj;
                output.write(String.valueOf(pageIndex));
                output.write("\t" + String.valueOf(image.x));
                output.write("\t" + String.valueOf(image.y));
                output.write("\t" + String.valueOf(image.w));
                output.write("\t" + String.valueOf(image.h));
                output.write("\n");
                i++;
            }
            else i++;
        }
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        if (!useImage) return;
        Image i = imageBuffer.get(0);
        buffer.add(i);
        imageBuffer.remove(0);
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
        float h = getPageHeight();
        addDraw("RECTANGLE", (float)p0.getX(), h - (float)p0.getY(), (float)p1.getX(), h - (float)p1.getY(),
                (float)p2.getX(), h - (float)p2.getY(), (float)p3.getX(), h - (float)p3.getY());
    }

    @Override
    public void clip(int i) throws IOException { }

    @Override
    public void moveTo(float x, float y) throws IOException {
        addDraw("MOVE_TO", x, getPageHeight() - y);
    }

    @Override
    public void lineTo(float x, float y) throws IOException {
        addDraw("LINE_TO", x, getPageHeight() - y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
        float h = getPageHeight();
        addDraw("CURVE_TO", x1, h - y1, x2, h - y2, x3, h - y3);
    }

    @Override
    public Point2D getCurrentPoint() throws IOException { return new Point2D.Float(0.0f, 0.0f); }

    @Override
    public void closePath() throws IOException { }

    @Override
    public void endPath() throws IOException { }

    @Override
    public void strokePath() throws IOException { addDraw("STROKE_PATH"); }

    @Override
    public void fillPath(int i) throws IOException { addDraw("FILL_PATH"); }

    @Override
    public void fillAndStrokePath(int i) throws IOException { addDraw("FILL_STROKE_PATH"); }

    @Override
    public void shadingFill(COSName cosName) throws IOException { }

    @Override
    public void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement) throws IOException {
        if (!useText) return;
        // taken from LegacyPDFStreamEngine.showGlyph
        PDGraphicsState state = this.getGraphicsState();
        Matrix ctm = state.getCurrentTransformationMatrix();
        float fontSize = state.getTextState().getFontSize();
        float horizontalScaling = state.getTextState().getHorizontalScaling() / 100.0F;
        Matrix textMatrix = this.getTextMatrix();
        BoundingBox bbox = font.getBoundingBox();
        if (bbox.getLowerLeftY() < -32768.0F) {
            bbox.setLowerLeftY(-(bbox.getLowerLeftY() + 65536.0F));
        }

        float glyphHeight = bbox.getHeight() / 2.0F;
        PDFontDescriptor fontDescriptor = font.getFontDescriptor();
        float height;
        if (fontDescriptor != null) {
            height = fontDescriptor.getCapHeight();
            if (height != 0.0F && (height < glyphHeight || glyphHeight == 0.0F)) {
                glyphHeight = height;
            }
        }

        height = glyphHeight / 1000.0F;

        float displacementX = displacement.getX();
        if (font.isVertical()) {
            displacementX = font.getWidth(code) / 1000.0F;
            TrueTypeFont ttf = null;
            if (font instanceof PDTrueTypeFont) {
                ttf = ((PDTrueTypeFont)font).getTrueTypeFont();
            } else if (font instanceof PDType0Font) {
                PDCIDFont cidFont = ((PDType0Font)font).getDescendantFont();
                if(cidFont instanceof PDCIDFontType2) {
                    ttf = ((PDCIDFontType2)cidFont).getTrueTypeFont();
                }
            }

            if (ttf != null && ttf.getUnitsPerEm() != 1000) {
                displacementX *= 1000.0F / (float)ttf.getUnitsPerEm();
            }
        }

        float tx = displacementX * fontSize * horizontalScaling;
        float ty = displacement.getY() * fontSize;
        Matrix td = Matrix.getTranslateInstance(tx, ty);
        Matrix nextTextRenderingMatrix = td.multiply(textMatrix).multiply(ctm);
        float nextX = nextTextRenderingMatrix.getTranslateX();
        float nextY = nextTextRenderingMatrix.getTranslateY();
        float dxDisplay = nextX - textRenderingMatrix.getTranslateX();
        float dyDisplay = height * textRenderingMatrix.getScalingFactorY();
        float glyphSpaceToTextSpaceFactor = 0.001F;
        if (font instanceof PDType3Font) {
            glyphSpaceToTextSpaceFactor = font.getFontMatrix().getScaleX();
        }

        float spaceWidthText = 0.0F;
        try {
            spaceWidthText = font.getSpaceWidth() * glyphSpaceToTextSpaceFactor;
        } catch (Throwable e) {
            //LOG.warn(e, e);
        }

        if (spaceWidthText == 0.0F) {
            spaceWidthText = font.getAverageFontWidth() * glyphSpaceToTextSpaceFactor;
            spaceWidthText *= 0.8F;
        }
        if (spaceWidthText == 0.0F) spaceWidthText = 1.0F;

        float spaceWidthDisplay = spaceWidthText * textRenderingMatrix.getScalingFactorX();
        unicode = font.toUnicode(code, this.glyphList);
        if (unicode == null) unicode = "[NO_UNICODE]";

        Matrix translatedTextRenderingMatrix;
        if (this.translateMatrix == null) {
            translatedTextRenderingMatrix = textRenderingMatrix;
        } else {
            translatedTextRenderingMatrix = Matrix.concatenate(this.translateMatrix, textRenderingMatrix);
            nextX -= this.pageSize.getLowerLeftX();
            nextY -= this.pageSize.getLowerLeftY();
        }

        TextPosition text = new TextPosition(pageRotation, pageSize.getWidth(), pageSize.getHeight(), translatedTextRenderingMatrix,
                nextX, nextY, Math.abs(dyDisplay), dxDisplay, Math.abs(spaceWidthDisplay),
                unicode, new int[]{code}, font, fontSize, (int)(fontSize * textMatrix.getScalingFactorX()));

        Shape boundingShape = calculateBounds(text);
        Rectangle2D.Double b = (Rectangle2D.Double)boundingShape.getBounds2D(); // bounding coordinates
        Shape glyphShape = calculateGlyphBounds(textRenderingMatrix, font, code);
        Rectangle2D.Double g = (Rectangle2D.Double)glyphShape.getBounds2D(); // glyph coordinates
        Text t = new Text(unicode, font, (float)b.x, (float)b.y, (float)b.width, (float)b.height,
                (float)g.x, (float)g.y, (float)g.width, (float)g.height);
        buffer.add(t);
    }

    // taken from writeString in DrawPrintTextLocations
    Shape calculateBounds(TextPosition text) throws IOException {
        // glyph space -> user space
        // note: text.getTextMatrix() is *not* the Text Matrix, it's the Text Rendering Matrix
        AffineTransform at = text.getTextMatrix().createAffineTransform();

        // show rectangle with the real vertical bounds, based on the font bounding box y values
        // usually, the height is identical to what you see when marking text in Adobe Reader
        PDFont font = text.getFont();
        BoundingBox bbox = font.getBoundingBox();

        // advance width, bbox height (glyph space)
        float xadvance = font.getWidth(text.getCharacterCodes()[0]); // todo: should iterate all chars
        Rectangle2D.Float rect = new Rectangle2D.Float(0, bbox.getLowerLeftY(), xadvance, bbox.getHeight());

        if (font instanceof PDType3Font) {
            // bbox and font matrix are unscaled
            at.concatenate(font.getFontMatrix().createAffineTransform());
        }
        else {
            // bbox and font matrix are already scaled to 1000
            at.scale(1/1000f, 1/1000f);
        }
        Shape s = at.createTransformedShape(rect);
        s = flipAT.createTransformedShape(s);
        s = rotateAT.createTransformedShape(s);
        return s;
    }

    // taken from DrawPrintTextLocations.java
    // this calculates the real (except for type 3 fonts) individual glyph bounds
    Shape calculateGlyphBounds(Matrix textRenderingMatrix, PDFont font, int code) throws IOException {
        GeneralPath path = null;
        AffineTransform at = textRenderingMatrix.createAffineTransform();
        at.concatenate(font.getFontMatrix().createAffineTransform());
        if (font instanceof PDType3Font) {
            // It is difficult to calculate the real individual glyph bounds for type 3 fonts
            // because these are not vector fonts, the content stream could contain almost anything
            // that is found in page content streams.
            PDType3Font t3Font = (PDType3Font) font;
            PDType3CharProc charProc = t3Font.getCharProc(code);
            if (charProc != null) {
                BoundingBox fontBBox = t3Font.getBoundingBox();
                PDRectangle glyphBBox = charProc.getGlyphBBox();
                if (glyphBBox != null) {
                    // PDFBOX-3850: glyph bbox could be larger than the font bbox
                    glyphBBox.setLowerLeftX(Math.max(fontBBox.getLowerLeftX(), glyphBBox.getLowerLeftX()));
                    glyphBBox.setLowerLeftY(Math.max(fontBBox.getLowerLeftY(), glyphBBox.getLowerLeftY()));
                    glyphBBox.setUpperRightX(Math.min(fontBBox.getUpperRightX(), glyphBBox.getUpperRightX()));
                    glyphBBox.setUpperRightY(Math.min(fontBBox.getUpperRightY(), glyphBBox.getUpperRightY()));
                    path = glyphBBox.toGeneralPath();
                }
            }
        }
        else if (font instanceof PDVectorFont) {
            PDVectorFont vectorFont = (PDVectorFont) font;
            path = vectorFont.getPath(code);

            if (font instanceof PDTrueTypeFont) {
                PDTrueTypeFont ttFont = (PDTrueTypeFont) font;
                int unitsPerEm = ttFont.getTrueTypeFont().getHeader().getUnitsPerEm();
                at.scale(1000d / unitsPerEm, 1000d / unitsPerEm);
            }
            if (font instanceof PDType0Font) {
                PDType0Font t0font = (PDType0Font) font;
                if (t0font.getDescendantFont() instanceof PDCIDFontType2) {
                    int unitsPerEm = ((PDCIDFontType2) t0font.getDescendantFont()).getTrueTypeFont().getHeader().getUnitsPerEm();
                    at.scale(1000d / unitsPerEm, 1000d / unitsPerEm);
                }
            }
        }
        else if (font instanceof PDSimpleFont) {
            PDSimpleFont simpleFont = (PDSimpleFont) font;

            // these two lines do not always work, e.g. for the TT fonts in file 032431.pdf
            // which is why PDVectorFont is tried first.
            String name = simpleFont.getEncoding().getName(code);
            path = simpleFont.getPath(name);
        }
        else {
            // shouldn't happen, please open issue in JIRA
            System.out.println("Unknown font class: " + font.getClass());
        }
        if (path == null) return null;
        Shape s = at.createTransformedShape(path.getBounds2D());
        s = flipAT.createTransformedShape(s);
        s = rotateAT.createTransformedShape(s);
        s = transAT.createTransformedShape(s);
        return s;
    }

    public class ImageExtractor extends PDFStreamEngine {

        List<Image> buffer = new ArrayList<>();

        public ImageExtractor() throws IOException {
            addOperator(new Concatenate());
            addOperator(new DrawObject());
            addOperator(new SetGraphicsStateParameters());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();
            if("Do".equals(operation)) {
                COSName objectName = (COSName)operands.get(0);
                PDXObject xobject = getResources().getXObject(objectName);

                if (xobject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject)xobject;
                    Matrix ctmNew = getGraphicsState().getCurrentTransformationMatrix();
                    PDRectangle pageRect = this.getCurrentPage().getCropBox();
                    float w = ctmNew.getScalingFactorX();
                    float h = ctmNew.getScalingFactorY();
                    float x = ctmNew.getTranslateX();
                    float y = pageRect.getHeight() - ctmNew.getTranslateY() - h;
                    buffer.add(new Image(x, y, w, h));
                }
                else if(xobject instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject)xobject;
                    showForm(form);
                }
            }
            else {
                super.processOperator(operator, operands);
            }
        }
    }
}
