package net.familylawandprobate.controversies;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates PDF compatibility against Texas e-filing technology standards
 * (Version 10.0, July 2025), Section 3.1 (A-G).
 */
public final class texas_pdf_compatibility_checker {

    public static final String STANDARDS_URL = "https://www.txcourts.gov/media/1435816/technology-standards.pdf";
    public static final String STANDARDS_LABEL = "Texas Technology Standards v10.0 (July 2025), Section 3.1";

    private static final float LETTER_WIDTH_PT = 612f;   // 8.5 * 72
    private static final float LETTER_HEIGHT_PT = 792f;  // 11 * 72
    private static final float LETTER_TOLERANCE_PT = 2f;
    private static final int MIN_TEXT_SEARCHABLE_CHARS = 16;
    private static final double MIN_SCAN_DPI = 300d;

    private static final Set<String> MULTIMEDIA_ANNOTATION_SUBTYPES = Set.of(
            "richmedia", "movie", "sound", "screen"
    );
    private static final Set<String> MULTIMEDIA_EXTENSIONS = Set.of(
            "mp3", "wav", "aac", "m4a", "ogg", "flac", "wma",
            "mp4", "mov", "avi", "wmv", "mkv", "webm", "mpeg", "mpg", "3gp"
    );
    private static final Set<String> DISTILLER_HINTS = Set.of(
            "distiller", "adobe pdf", "adobe acrobat", "print to pdf",
            "ghostscript", "libreoffice", "microsoft word", "acrobat", "pdfbox"
    );

    private texas_pdf_compatibility_checker() {
    }

    public enum Status {
        PASS,
        WARN,
        FAIL,
        INFO
    }

    public static final class CheckResult {
        public final String code;
        public final String requirement;
        public final Status status;
        public final String detail;

        public CheckResult(String code, String requirement, Status status, String detail) {
            this.code = safe(code);
            this.requirement = safe(requirement);
            this.status = status == null ? Status.INFO : status;
            this.detail = safe(detail);
        }
    }

    public static final class Report {
        public final String standardsUrl;
        public final String standardsLabel;
        public final String checkedAt;
        public final String fileName;
        public final String pdfVersion;
        public final int pageCount;
        public final int failCount;
        public final int warnCount;
        public final boolean compatible;
        public final List<CheckResult> checks;

        public Report(String standardsUrl,
                      String standardsLabel,
                      String checkedAt,
                      String fileName,
                      String pdfVersion,
                      int pageCount,
                      int failCount,
                      int warnCount,
                      boolean compatible,
                      List<CheckResult> checks) {
            this.standardsUrl = safe(standardsUrl);
            this.standardsLabel = safe(standardsLabel);
            this.checkedAt = safe(checkedAt);
            this.fileName = safe(fileName);
            this.pdfVersion = safe(pdfVersion);
            this.pageCount = Math.max(0, pageCount);
            this.failCount = Math.max(0, failCount);
            this.warnCount = Math.max(0, warnCount);
            this.compatible = compatible;
            this.checks = checks == null ? List.of() : Collections.unmodifiableList(new ArrayList<CheckResult>(checks));
        }
    }

    private static final class PageGeometrySummary {
        int nonLetterPages = 0;
        int rotationMismatchPages = 0;
    }

    private static final class ScanDpiSummary {
        int textlessPages = 0;
        int dpiMeasuredPages = 0;
        double minMeasuredDpi = Double.POSITIVE_INFINITY;
    }

    private static final class EmbeddedFileSummary {
        int totalEmbeddedFiles = 0;
        int embeddedPdfFiles = 0;
        int multimediaEmbeddedFiles = 0;
    }

    private static final class AnnotationSummary {
        int multimediaAnnotations = 0;
        int attachedFiles = 0;
        int attachedPdfFiles = 0;
        int attachedMultimediaFiles = 0;
    }

    private static final class ImagePixelSize {
        int maxWidthPx = 0;
        int maxHeightPx = 0;
    }

    public static Report evaluate(Path pdfPath) throws Exception {
        Path path = requirePdf(pdfPath);
        String fileName = path.getFileName() == null ? "" : safe(path.getFileName().toString());
        ArrayList<CheckResult> checks = new ArrayList<CheckResult>();

        try (PDDocument doc = PDDocument.load(path.toFile())) {
            int pageCount = Math.max(0, doc.getNumberOfPages());
            String pdfVersion = toPdfVersionLabel(doc.getVersion());
            String searchableText = extractText(doc);
            boolean searchable = searchableText.trim().length() >= MIN_TEXT_SEARCHABLE_CHARS;

            PageGeometrySummary pageGeometry = inspectPageGeometry(doc);
            ScanDpiSummary scanDpi = inspectScanDpi(doc);
            EmbeddedFileSummary embedded = inspectEmbeddedFiles(doc);
            AnnotationSummary annotations = inspectAnnotations(doc);
            int embeddedFonts = countEmbeddedFonts(doc);
            boolean encrypted = doc.isEncrypted();
            boolean javascript = containsJavaScript(doc);
            boolean portfolio = isPortfolio(doc);

            checks.add(checkA(pdfVersion, searchable, pageCount, pageGeometry));
            checks.add(checkB(doc.getDocumentInformation()));
            checks.add(checkC(searchable, scanDpi));
            checks.add(checkD(encrypted, annotations, embedded, javascript));
            checks.add(checkE(portfolio, embedded, annotations, embeddedFonts));
            checks.add(checkF(fileName));
            checks.add(checkG());

            int failCount = 0;
            int warnCount = 0;
            for (CheckResult c : checks) {
                if (c == null) continue;
                if (c.status == Status.FAIL) failCount++;
                if (c.status == Status.WARN) warnCount++;
            }

            return new Report(
                    STANDARDS_URL,
                    STANDARDS_LABEL,
                    Instant.now().toString(),
                    fileName,
                    pdfVersion,
                    pageCount,
                    failCount,
                    warnCount,
                    failCount == 0,
                    checks
            );
        }
    }

    private static CheckResult checkA(String pdfVersion, boolean searchable, int pageCount, PageGeometrySummary pageGeometry) {
        ArrayList<String> issues = new ArrayList<String>();
        if (!isAcceptablePdfVersion(pdfVersion)) {
            issues.add("PDF version is " + safe(pdfVersion) + "; expected 1.4 through 1.7.");
        }
        if (pageCount <= 0) {
            issues.add("PDF contains no pages.");
        }
        if (!searchable) {
            issues.add("Document text does not appear searchable.");
        }
        if (pageGeometry != null && pageGeometry.nonLetterPages > 0) {
            issues.add(pageGeometry.nonLetterPages + " page(s) are not 8.5 x 11 inches.");
        }
        if (pageGeometry != null && pageGeometry.rotationMismatchPages > 0) {
            issues.add(pageGeometry.rotationMismatchPages + " page(s) appear improperly rotated for page orientation.");
        }

        if (!issues.isEmpty()) {
            return new CheckResult(
                    "3.1.A",
                    "PDF must be v1.4-1.7, text-searchable, and formatted for 8.5x11 pages with proper rotation.",
                    Status.FAIL,
                    joinDetails(issues)
            );
        }
        return new CheckResult(
                "3.1.A",
                "PDF must be v1.4-1.7, text-searchable, and formatted for 8.5x11 pages with proper rotation.",
                Status.PASS,
                "PDF version, text-searchability, page size, and rotation checks passed."
        );
    }

    private static CheckResult checkB(PDDocumentInformation info) {
        String producer = safe(info == null ? "" : info.getProducer()).trim();
        String lower = producer.toLowerCase(Locale.ROOT);
        for (String hint : DISTILLER_HINTS) {
            if (lower.contains(hint)) {
                return new CheckResult(
                        "3.1.B",
                        "Document should be generated directly from the source document software when possible.",
                        Status.PASS,
                        "Producer metadata suggests direct generation: " + producer
                );
            }
        }
        if (producer.isBlank()) {
            return new CheckResult(
                    "3.1.B",
                    "Document should be generated directly from the source document software when possible.",
                    Status.WARN,
                    "Producer metadata is missing; direct generation could not be confirmed."
            );
        }
        return new CheckResult(
                "3.1.B",
                "Document should be generated directly from the source document software when possible.",
                Status.WARN,
                "Producer metadata did not match known direct-generation tools: " + producer
        );
    }

    private static CheckResult checkC(boolean searchable, ScanDpiSummary scanDpi) {
        if (searchable) {
            return new CheckResult(
                    "3.1.C",
                    "Scanned documents should be 300 DPI and text-searchable prior to filing.",
                    Status.PASS,
                    "Searchable text detected."
            );
        }

        if (scanDpi == null || scanDpi.textlessPages <= 0) {
            return new CheckResult(
                    "3.1.C",
                    "Scanned documents should be 300 DPI and text-searchable prior to filing.",
                    Status.FAIL,
                    "No searchable text detected."
            );
        }

        if (scanDpi.dpiMeasuredPages <= 0 || Double.isInfinite(scanDpi.minMeasuredDpi)) {
            return new CheckResult(
                    "3.1.C",
                    "Scanned documents should be 300 DPI and text-searchable prior to filing.",
                    Status.WARN,
                    "Text was not searchable and scan DPI could not be estimated automatically."
            );
        }

        if (scanDpi.minMeasuredDpi < MIN_SCAN_DPI) {
            return new CheckResult(
                    "3.1.C",
                    "Scanned documents should be 300 DPI and text-searchable prior to filing.",
                    Status.FAIL,
                    "Estimated minimum scan DPI is " + String.format(Locale.US, "%.1f", scanDpi.minMeasuredDpi) + " (< 300)."
            );
        }

        return new CheckResult(
                "3.1.C",
                "Scanned documents should be 300 DPI and text-searchable prior to filing.",
                Status.FAIL,
                "No searchable text detected."
        );
    }

    private static CheckResult checkD(boolean encrypted,
                                      AnnotationSummary annotations,
                                      EmbeddedFileSummary embedded,
                                      boolean javascript) {
        ArrayList<String> issues = new ArrayList<String>();
        if (encrypted) {
            issues.add("PDF is encrypted or password protected.");
        }
        if (annotations != null && annotations.multimediaAnnotations > 0) {
            issues.add(annotations.multimediaAnnotations + " multimedia annotation(s) detected.");
        }
        int multimediaEmbedded = 0;
        if (embedded != null) multimediaEmbedded += embedded.multimediaEmbeddedFiles;
        if (annotations != null) multimediaEmbedded += annotations.attachedMultimediaFiles;
        if (multimediaEmbedded > 0) {
            issues.add(multimediaEmbedded + " embedded multimedia file(s) detected.");
        }
        if (javascript) {
            issues.add("JavaScript actions detected.");
        }
        if (!issues.isEmpty()) {
            return new CheckResult(
                    "3.1.D",
                    "Document must not be encrypted, must not include multimedia, and must not include JavaScript.",
                    Status.FAIL,
                    joinDetails(issues)
            );
        }
        return new CheckResult(
                "3.1.D",
                "Document must not be encrypted, must not include multimedia, and must not include JavaScript.",
                Status.PASS,
                "No encryption, multimedia, or JavaScript indicators were found."
        );
    }

    private static CheckResult checkE(boolean portfolio,
                                      EmbeddedFileSummary embedded,
                                      AnnotationSummary annotations,
                                      int embeddedFonts) {
        ArrayList<String> issues = new ArrayList<String>();
        if (portfolio) {
            issues.add("PDF portfolio/package structure detected.");
        }

        int embeddedFiles = embedded == null ? 0 : embedded.totalEmbeddedFiles;
        int embeddedPdfs = embedded == null ? 0 : embedded.embeddedPdfFiles;
        if (annotations != null) {
            embeddedFiles += annotations.attachedFiles;
            embeddedPdfs += annotations.attachedPdfFiles;
        }

        if (embeddedFiles > 0) {
            issues.add(embeddedFiles + " embedded/attached file(s) detected.");
        }
        if (embeddedPdfs > 0) {
            issues.add(embeddedPdfs + " embedded PDF file(s) detected.");
        }
        if (embeddedFonts > 0) {
            issues.add(embeddedFonts + " embedded font resource(s) detected.");
        }

        if (!issues.isEmpty()) {
            return new CheckResult(
                    "3.1.E",
                    "Document must be a single PDF with no embedded PDFs/files and no embedded fonts.",
                    Status.FAIL,
                    joinDetails(issues)
            );
        }
        return new CheckResult(
                "3.1.E",
                "Document must be a single PDF with no embedded PDFs/files and no embedded fonts.",
                Status.PASS,
                "No portfolio structures, embedded files, or embedded fonts were found."
        );
    }

    private static CheckResult checkF(String fileName) {
        String name = safe(fileName).trim();
        String base = name;
        if (base.toLowerCase(Locale.ROOT).endsWith(".pdf") && base.length() > 4) {
            base = base.substring(0, base.length() - 4);
        }
        boolean charsOk = !base.isBlank() && base.matches("[A-Za-z0-9]+");
        boolean lengthOk = base.length() <= 50;
        if (!charsOk || !lengthOk) {
            ArrayList<String> issues = new ArrayList<String>();
            if (!charsOk) issues.add("Filename base contains non-alphanumeric characters.");
            if (!lengthOk) issues.add("Filename base exceeds 50 characters.");
            return new CheckResult(
                    "3.1.F",
                    "Filename should be <= 50 characters and use Latin1 alphanumeric characters only.",
                    Status.FAIL,
                    "File name '" + name + "': " + joinDetails(issues)
            );
        }
        return new CheckResult(
                "3.1.F",
                "Filename should be <= 50 characters and use Latin1 alphanumeric characters only.",
                Status.PASS,
                "Filename base '" + base + "' is alphanumeric and <= 50 characters."
        );
    }

    private static CheckResult checkG() {
        return new CheckResult(
                "3.1.G",
                "Operational filing requirements should be reviewed prior to filing.",
                Status.INFO,
                "This requirement is policy/process oriented and cannot be validated from PDF bytes alone."
        );
    }

    private static boolean isAcceptablePdfVersion(String pdfVersionLabel) {
        String v = safe(pdfVersionLabel).trim().toLowerCase(Locale.ROOT);
        return "1.4".equals(v) || "1.5".equals(v) || "1.6".equals(v) || "1.7".equals(v);
    }

    private static String toPdfVersionLabel(float version) {
        return String.format(Locale.US, "%.1f", version);
    }

    private static Path requirePdf(Path pdfPath) {
        if (pdfPath == null) throw new IllegalArgumentException("PDF path is required.");
        Path p = pdfPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("PDF file not found.");
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) throw new IllegalArgumentException("Expected a .pdf file.");
        return p;
    }

    private static String extractText(PDDocument doc) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String text = stripper.getText(doc);
        return safe(text);
    }

    private static PageGeometrySummary inspectPageGeometry(PDDocument doc) {
        PageGeometrySummary out = new PageGeometrySummary();
        PDPageTree pages = doc.getPages();
        for (PDPage page : pages) {
            if (page == null) continue;
            PDRectangle box = page.getCropBox();
            if (box == null) box = page.getMediaBox();
            if (box == null) {
                out.nonLetterPages++;
                continue;
            }
            float w = Math.max(0f, box.getWidth());
            float h = Math.max(0f, box.getHeight());
            boolean letterPortrait = approx(w, LETTER_WIDTH_PT, LETTER_TOLERANCE_PT)
                    && approx(h, LETTER_HEIGHT_PT, LETTER_TOLERANCE_PT);
            boolean letterLandscape = approx(w, LETTER_HEIGHT_PT, LETTER_TOLERANCE_PT)
                    && approx(h, LETTER_WIDTH_PT, LETTER_TOLERANCE_PT);
            if (!letterPortrait && !letterLandscape) {
                out.nonLetterPages++;
            }
            int rotation = normalizeRotation(page.getRotation());
            boolean landscape = w > h;
            boolean rotateLooksOk = landscape
                    ? (rotation == 90 || rotation == 270)
                    : (rotation == 0 || rotation == 180);
            if (!rotateLooksOk) out.rotationMismatchPages++;
        }
        return out;
    }

    private static ScanDpiSummary inspectScanDpi(PDDocument doc) {
        ScanDpiSummary out = new ScanDpiSummary();
        PDFTextStripper stripper;
        try {
            stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
        } catch (Exception ex) {
            return out;
        }
        int total = Math.max(0, doc.getNumberOfPages());
        for (int pageIndex = 0; pageIndex < total; pageIndex++) {
            String pageText = "";
            try {
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                pageText = safe(stripper.getText(doc));
            } catch (Exception ignored) {
                pageText = "";
            }
            if (pageText.trim().length() >= MIN_TEXT_SEARCHABLE_CHARS) continue;

            out.textlessPages++;
            PDPage page = doc.getPage(pageIndex);
            if (page == null) continue;

            PDRectangle box = page.getCropBox();
            if (box == null) box = page.getMediaBox();
            if (box == null) continue;
            double pageWidthIn = Math.max(1d / 72d, box.getWidth() / 72d);
            double pageHeightIn = Math.max(1d / 72d, box.getHeight() / 72d);

            ImagePixelSize imagePx = new ImagePixelSize();
            collectMaxImagePixels(page.getResources(), imagePx, Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>()));
            if (imagePx.maxWidthPx <= 0 || imagePx.maxHeightPx <= 0) continue;

            double dpiX = imagePx.maxWidthPx / pageWidthIn;
            double dpiY = imagePx.maxHeightPx / pageHeightIn;
            double minDpi = Math.min(dpiX, dpiY);
            if (Double.isFinite(minDpi) && minDpi > 0d) {
                out.dpiMeasuredPages++;
                out.minMeasuredDpi = Math.min(out.minMeasuredDpi, minDpi);
            }
        }
        return out;
    }

    private static EmbeddedFileSummary inspectEmbeddedFiles(PDDocument doc) {
        EmbeddedFileSummary out = new EmbeddedFileSummary();
        try {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            if (catalog == null) return out;
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
            PDEmbeddedFilesNameTreeNode tree = names.getEmbeddedFiles();
            ArrayList<PDComplexFileSpecification> all = new ArrayList<PDComplexFileSpecification>();
            collectEmbeddedFiles(tree, all);
            out.totalEmbeddedFiles = all.size();
            for (PDComplexFileSpecification fs : all) {
                if (fs == null) continue;
                String fileName = embeddedFileName(fs).toLowerCase(Locale.ROOT);
                if (fileName.endsWith(".pdf")) out.embeddedPdfFiles++;
                if (isMultimediaFile(fileName, embeddedSubtype(fs))) out.multimediaEmbeddedFiles++;
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    private static AnnotationSummary inspectAnnotations(PDDocument doc) {
        AnnotationSummary out = new AnnotationSummary();
        PDPageTree pages = doc.getPages();
        for (PDPage page : pages) {
            if (page == null) continue;
            List<PDAnnotation> rows;
            try {
                rows = page.getAnnotations();
            } catch (Exception ex) {
                continue;
            }
            for (PDAnnotation a : rows) {
                if (a == null) continue;
                String subtype = safe(a.getSubtype()).trim().toLowerCase(Locale.ROOT);
                if (MULTIMEDIA_ANNOTATION_SUBTYPES.contains(subtype)) {
                    out.multimediaAnnotations++;
                }
                if ("fileattachment".equals(subtype) && a instanceof PDAnnotationFileAttachment fa) {
                    out.attachedFiles++;
                    try {
                        if (fa.getFile() instanceof PDComplexFileSpecification fs) {
                            String fileName = embeddedFileName(fs).toLowerCase(Locale.ROOT);
                            if (fileName.endsWith(".pdf")) out.attachedPdfFiles++;
                            if (isMultimediaFile(fileName, embeddedSubtype(fs))) out.attachedMultimediaFiles++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return out;
    }

    private static int countEmbeddedFonts(PDDocument doc) {
        Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        Set<COSBase> visitedFonts = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        int count = 0;
        for (PDPage page : doc.getPages()) {
            if (page == null) continue;
            count += countEmbeddedFonts(page.getResources(), visitedResources, visitedFonts);
        }
        return count;
    }

    private static int countEmbeddedFonts(PDResources resources, Set<COSBase> visitedResources, Set<COSBase> visitedFonts) {
        if (resources == null) return 0;
        COSDictionary dict = resources.getCOSObject();
        if (dict != null && !visitedResources.add(dict)) return 0;
        int count = 0;

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                if (font == null) continue;
                COSBase fontBase = font.getCOSObject();
                if (fontBase != null && !visitedFonts.add(fontBase)) continue;
                PDFontDescriptor d = font.getFontDescriptor();
                if (d != null && (d.getFontFile() != null || d.getFontFile2() != null || d.getFontFile3() != null)) {
                    count++;
                }
            } catch (Exception ignored) {
            }
        }

        for (COSName xName : resources.getXObjectNames()) {
            try {
                PDXObject x = resources.getXObject(xName);
                if (x instanceof PDFormXObject form) {
                    count += countEmbeddedFonts(form.getResources(), visitedResources, visitedFonts);
                }
            } catch (Exception ignored) {
            }
        }

        return count;
    }

    private static boolean containsJavaScript(PDDocument doc) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        if (catalog == null) return false;
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        return containsJavaScript(catalog.getCOSObject(), seen);
    }

    private static boolean containsJavaScript(COSBase base, Set<COSBase> seen) {
        if (base == null) return false;
        if (!seen.add(base)) return false;
        if (base instanceof COSObject obj) {
            return containsJavaScript(obj.getObject(), seen);
        }
        if (base instanceof COSDictionary dict) {
            if (dict.containsKey(COSName.JAVA_SCRIPT)) return true;
            COSBase actionType = dict.getDictionaryObject(COSName.S);
            if (actionType instanceof COSName actionName) {
                if ("JavaScript".equalsIgnoreCase(actionName.getName())) return true;
            }
            for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
                if (e == null) continue;
                COSName key = e.getKey();
                if (key != null && "JavaScript".equalsIgnoreCase(key.getName())) return true;
                if (containsJavaScript(e.getValue(), seen)) return true;
            }
            return false;
        }
        if (base instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (containsJavaScript(arr.get(i), seen)) return true;
            }
            return false;
        }
        if (base instanceof COSString s) {
            String text = safe(s.getString()).toLowerCase(Locale.ROOT);
            return text.contains("javascript:");
        }
        return false;
    }

    private static boolean isPortfolio(PDDocument doc) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        if (catalog == null || catalog.getCOSObject() == null) return false;
        return catalog.getCOSObject().containsKey(COSName.COLLECTION);
    }

    private static void collectEmbeddedFiles(PDEmbeddedFilesNameTreeNode node, List<PDComplexFileSpecification> out) throws IOException {
        if (node == null) return;
        Map<String, PDComplexFileSpecification> names = node.getNames();
        if (names != null) {
            for (PDComplexFileSpecification fs : names.values()) {
                if (fs != null) out.add(fs);
            }
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids == null) return;
        for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) {
            if (kid instanceof PDEmbeddedFilesNameTreeNode embeddedKid) {
                collectEmbeddedFiles(embeddedKid, out);
            }
        }
    }

    private static String embeddedFileName(PDComplexFileSpecification fs) {
        if (fs == null) return "";
        String name = safe(fs.getFilename()).trim();
        if (!name.isBlank()) return name;
        name = safe(fs.getFileUnicode()).trim();
        if (!name.isBlank()) return name;
        name = safe(fs.getFile()).trim();
        if (!name.isBlank()) return name;
        name = safe(fs.getFileDos()).trim();
        if (!name.isBlank()) return name;
        name = safe(fs.getFileMac()).trim();
        if (!name.isBlank()) return name;
        name = safe(fs.getFileUnix()).trim();
        if (!name.isBlank()) return name;
        return "";
    }

    private static String embeddedSubtype(PDComplexFileSpecification fs) {
        if (fs == null) return "";
        try {
            PDEmbeddedFile ef = fs.getEmbeddedFile();
            if (ef == null) return "";
            return safe(ef.getSubtype()).trim().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isMultimediaFile(String fileName, String subtype) {
        String n = safe(fileName).trim().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 && dot + 1 < n.length() ? n.substring(dot + 1) : "";
        if (MULTIMEDIA_EXTENSIONS.contains(ext)) return true;
        String st = safe(subtype).trim().toLowerCase(Locale.ROOT);
        return st.startsWith("audio/") || st.startsWith("video/");
    }

    private static void collectMaxImagePixels(PDResources resources, ImagePixelSize out, Set<COSBase> visitedResources) {
        if (resources == null || out == null) return;
        COSDictionary dict = resources.getCOSObject();
        if (dict != null && !visitedResources.add(dict)) return;
        for (COSName xName : resources.getXObjectNames()) {
            try {
                PDXObject x = resources.getXObject(xName);
                if (x instanceof PDImageXObject img) {
                    out.maxWidthPx = Math.max(out.maxWidthPx, Math.max(0, img.getWidth()));
                    out.maxHeightPx = Math.max(out.maxHeightPx, Math.max(0, img.getHeight()));
                } else if (x instanceof PDFormXObject form) {
                    collectMaxImagePixels(form.getResources(), out, visitedResources);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static int normalizeRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) r += 360;
        return r;
    }

    private static boolean approx(float a, float b, float tol) {
        return Math.abs(a - b) <= tol;
    }

    private static String joinDetails(List<String> details) {
        if (details == null || details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < details.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append("- ").append(safe(details.get(i)));
        }
        return sb.toString().trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
