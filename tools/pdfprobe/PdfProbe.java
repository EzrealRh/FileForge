import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 语义实验台：只在本机 JVM 上跑，用桌面版 PDFBox 2.0.27。pdfbox-android 是同版本 PDFBox 的
 * 图形层替换版，页手术与对象图语义一致，所以这里量到的结论可以直接指导安卓侧实现。
 * 用法见 run.sh；每个子命令打印一行行可读结论。
 */
public final class PdfProbe {

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "make-inherited" -> makeInherited(new File(args[1]));
            case "make-smask" -> makeSmask(new File(args[1]));
            case "make-images" -> makeImages(new File(args[1]), Integer.parseInt(args[2]));
            case "copy" -> copy(new File(args[1]), new File(args[2]), parsePages(args[3]), args[4].equals("fix"));
            case "compress" -> compress(new File(args[1]), new File(args[2]),
                    Integer.parseInt(args[3]), Float.parseFloat(args[4]));
            case "check" -> check(new File(args[1]));
            case "strict" -> strict(new File(args[1]), true);
            case "rotate" -> rotate(new File(args[1]), new File(args[2]), parsePages(args[3]), Integer.parseInt(args[4]));
            case "verify" -> verify(new File(args[1]));
            default -> throw new IllegalArgumentException("unknown cmd " + args[0]);
        }
    }

    /** 旋转：和 PdfEngine.copyPages 同样的路数（importPage + 补资源 + setRotation）。 */
    private static void rotate(File source, File target, List<Integer> pages, int degrees) throws Exception {
        try (PDDocument src = PDDocument.load(source); PDDocument dst = new PDDocument()) {
            for (int index : pages) {
                PDPage from = src.getPage(index);
                PDPage to = dst.importPage(from);
                PDResources resources = from.getResources();
                if (resources != null) to.setResources(resources);
                to.setRotation((from.getRotation() + degrees) % 360);
            }
            dst.save(target);
        }
        System.out.println("rotate " + target + " 每页 +" + degrees + "°");
    }

    /**
     * 结构体检：页树的 /Parent 是否指向本文档自己的 Pages 节点、/Count 对不对、MediaBox 是否为正、
     * 间接对象总数（用来抓「手术时把源文档的对象整串拖进结果文件」这种毛病）。
     */
    private static int strict(File file, boolean verbose) throws Exception {
        try (PDDocument doc = PDDocument.load(file)) {
            COSDictionary root = (COSDictionary) doc.getPages().getCOSObject();
            int objects = doc.getDocument().getObjects().size();
            if (verbose) {
                System.out.println("strict " + file + " " + file.length() + " B，页数=" + doc.getNumberOfPages()
                        + "，间接对象=" + objects);
            }
            List<String> problems = new ArrayList<>();
            if (doc.getPages().getCount() != doc.getNumberOfPages()) problems.add("/Count 与实际页树不一致");
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                COSDictionary dict = page.getCOSObject();
                COSBase parent = dict.getItem(COSName.PARENT);
                if (parent == null) {
                    problems.add("第" + (i + 1) + " 页没有 /Parent");
                } else if (!reachesRoot(root, deref(parent), 0)) {
                    problems.add("第" + (i + 1) + " 页的 /Parent 不在本文档页树上：" + deref(parent));
                }
                PDRectangle box = page.getMediaBox();
                if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) problems.add("第" + (i + 1) + " 页 MediaBox 不正常");
                if (page.getResources() == null) problems.add("第" + (i + 1) + " 页没有资源（本页内容可能画不出来）");
            }
            for (String problem : problems) System.out.println("  结构问题：" + problem);
            if (problems.isEmpty() && verbose) System.out.println("  结构检查通过");
            return objects;
        }
    }

    /** 断言版跑一遍全部实验：页手术补资源必须让渲染结果和原文件一致，压缩必须真降体积且页页有内容。 */
    private static void verify(File dir) throws Exception {
        dir.mkdirs();
        File inherited = new File(dir, "inherited.pdf");
        makeInherited(inherited);
        double[] baseline = inkFraction(inherited);
        check(inherited);
        expect(baseline[0] > 5 && baseline[2] > 5, "对照文件每页都该有内容");

        copy(inherited, new File(dir, "copy-naive.pdf"), List.of(0, 2), false);
        copy(inherited, new File(dir, "copy-fix.pdf"), List.of(0, 2), true);
        double[] naive = inkFraction(new File(dir, "copy-naive.pdf"));
        double[] fixed = inkFraction(new File(dir, "copy-fix.pdf"));
        System.out.printf("  继承资源页：原 %.2f%%  不补资源 %.2f%%（差 %.2f）  补了 %.2f%%%n",
                baseline[0], naive[0], baseline[0] - naive[0], fixed[0]);
        expect(Math.abs(fixed[0] - baseline[0]) < 0.05 && Math.abs(fixed[1] - baseline[2]) < 0.05,
                "补资源后渲染要和原文件一致");
        expect(naive[0] < baseline[0] - 0.05, "不补资源就该看到丢内容（这条不过说明 PDFBox 行为变了）");

        int whole = strict(inherited, true);
        int part = strict(new File(dir, "copy-fix.pdf"), true);
        expect(part < whole, "只复制两页不该把整份的对象都拖进结果（" + whole + " → " + part + "）");
        rotate(inherited, new File(dir, "rotated.pdf"), List.of(0, 1, 2), 90);
        strict(new File(dir, "rotated.pdf"), true);
        try (PDDocument doc = PDDocument.load(new File(dir, "rotated.pdf"))) {
            expect(doc.getPage(0).getRotation() == 90 && doc.getPage(1).getRotation() == 90, "旋转要落在每一页上");
        }
        File rotated = new File(dir, "rotated.pdf");
        String rotatedText = textOf(rotated);
        expect(rotatedText.contains("MARKER1") && rotatedText.contains("MARKER3"), "旋转后文字还要能读出来");
        double[] turned = inkFraction(rotated);
        expect(turned[0] > 5 && turned[2] > 5, "旋转后每页还都有内容");
        expect(turned.length == 3, "旋转不改页数");

        File images = new File(dir, "img.pdf");
        makeImages(images, 4);
        double[] before = inkFraction(images);
        File packed = new File(dir, "img-compressed.pdf");
        compress(images, packed, 1000, 0.52f);
        double[] after = inkFraction(packed);
        strict(packed, true);
        expect(packed.length() * 3 < images.length(), "压缩得把体积明显降下来");
        expect(after.length == before.length, "页数不能变");
        for (int i = 0; i < before.length; i++) {
            expect(Math.abs(after[i] - before[i]) < 2.0, "第 " + (i + 1) + " 页压完还是那张图（" + before[i] + "→" + after[i] + "）");
        }
        expect(textOf(packed).contains("PAGE1"), "文字层不能丢");

        File masked = new File(dir, "smask.pdf");
        makeSmask(masked);
        File maskedPacked = new File(dir, "smask-compressed.pdf");
        compress(masked, maskedPacked, 300, 0.5f);
        try (PDDocument doc = PDDocument.load(maskedPacked)) {
            PDImageXObject kept = firstImage(doc);
            PDImageXObject soft = kept.getSoftMask();
            expect(soft != null, "压缩不能把透明掩膜弄丢");
            expect("DeviceGray".equals(soft.getColorSpace().getName()),
                    "掩膜必须还是单通道灰度（规范硬要求），实际 " + soft.getColorSpace().getName());
            expect(kept.getWidth() == soft.getWidth() && kept.getHeight() == soft.getHeight(),
                    "掩膜尺寸要跟图一致：" + kept.getWidth() + "x" + kept.getHeight() + " vs " + soft.getWidth() + "x" + soft.getHeight());
        }
        System.out.println("verify PASS");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  通过：" : "  失败：") + what);
        if (!ok) throw new AssertionError(what);
    }

    private static COSBase deref(COSBase base) {
        return base instanceof COSObject object ? object.getObject() : base;
    }

    /** 页的 /Parent 允许指向中间 /Pages 节点，但必须能沿链走到本文档的页树根。 */
    private static boolean reachesRoot(COSDictionary root, COSBase node, int depth) {
        if (depth > 8 || !(node instanceof COSDictionary dict)) return false;
        if (dict == root) return true;
        COSBase parent = dict.getItem(COSName.PARENT);
        return parent != null && reachesRoot(root, deref(parent), depth + 1);
    }

    private static double[] inkFraction(File file) throws Exception {
        try (PDDocument doc = PDDocument.load(file)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            double[] out = new double[doc.getNumberOfPages()];
            for (int i = 0; i < out.length; i++) out[i] = ink(renderer.renderImageWithDPI(i, 50));
            return out;
        }
    }

    private static String textOf(File file) throws Exception {
        try (PDDocument doc = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).replaceAll("\\s+", " ").trim();
        }
    }

    private static double ink(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) < 245 || ((rgb >> 8) & 0xFF) < 245 || (rgb & 0xFF) < 245) count++;
            }
        }
        return count * 100.0 / ((long) image.getWidth() * image.getHeight());
    }

    private static List<Integer> parsePages(String csv) {
        List<Integer> pages = new ArrayList<>();
        for (String part : csv.split(",")) pages.add(Integer.parseInt(part.trim()) - 1);
        return pages;
    }

    /** 造一份「页面不带 /Resources，资源挂在 /Pages 根节点」的文件：规范允许的继承资源，扫描件和排版软件常见。 */
    private static void makeInherited(File out) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(new PDRectangle(320, 220));
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA_BOLD, 20);
                    stream.newLineAtOffset(24, 120);
                    stream.showText("MARKER" + (i + 1));
                    stream.endText();
                    stream.setNonStrokingColor(0.15f, 0.35f, 0.85f);
                    stream.addRect(24, 30, 150, 44);
                    stream.fill();
                }
            }
            doc.save(out);
        }
        try (PDDocument doc = PDDocument.load(out)) {
            COSDictionary root = (COSDictionary) doc.getPages().getCOSObject();
            COSBase moved = doc.getPage(0).getCOSObject().getItem(COSName.RESOURCES);
            doc.getPage(0).getCOSObject().removeItem(COSName.RESOURCES);
            if (moved != null) root.setItem(COSName.RESOURCES, moved);
            doc.getPage(2).getCOSObject().removeItem(COSName.RESOURCES);
            doc.save(out);
        }
        System.out.println("make-inherited " + out + "：第1页资源搬到 /Pages 根节点，第3页改为完全靠继承");
    }

    /** 造一张带软掩膜的图：掩膜本身也是 /Subtype /Image 的 DeviceGray 图，压缩时最容易顺手被重编坏掉。 */
    private static void makeSmask(File out) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 220));
            doc.addPage(page);
            BufferedImage body = picture(600, 440, 9);
            BufferedImage alpha = new BufferedImage(600, 440, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < 440; y++) {
                for (int x = 0; x < 600; x++) {
                    // 起伏大的掩膜：Flate 压不动，才可能被重编成 JPEG 而被换掉（平掩膜不会走到那条路）
                    int v = (x * 3 + y * 5 + ((x * y) % 31) * 4) & 0xFF;
                    alpha.setRGB(x, y, (v << 16) | (v << 8) | v);
                }
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, body);
            PDImageXObject mask = LosslessFactory.createFromImage(doc, alpha);
            image.getCOSObject().setItem(COSName.SMASK, new COSObject(mask.getCOSObject()));
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.drawImage(image, 20, 20, 260, 180);
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 14);
                stream.newLineAtOffset(20, 12);
                stream.showText("MASKED");
                stream.endText();
            }
            doc.save(out);
        }
        try (PDDocument doc = PDDocument.load(out)) {
            PDImageXObject first = firstImage(doc);
            PDImageXObject soft = first.getSoftMask();
            System.out.println("make-smask " + out + "：图 " + first.getWidth() + "x" + first.getHeight()
                    + " 色彩 " + first.getColorSpace().getName()
                    + "，掩膜 " + (soft == null ? "没挂上" : soft.getWidth() + "x" + soft.getHeight() + " " + soft.getColorSpace().getName()));
        }
    }

    private static PDImageXObject firstImage(PDDocument doc) throws IOException {
        for (COSObject reference : doc.getDocument().getObjects()) {
            if (!(reference.getObject() instanceof COSStream stream)) continue;
            if (stream.getDictionaryObject(COSName.SUBTYPE) == COSName.IMAGE) return new PDImageXObject(new PDStream(stream), null);
        }
        throw new IllegalStateException("文件里没有图片");
    }

    /** 造一份图片很多的文件：每页一张不同的大图，外加一张全篇共用的图，用来测压缩比与共享对象。 */
    private static void makeImages(File out, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject shared = JPEGFactory.createFromImage(doc, picture(900, 900, 77), 0.95f);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(new PDRectangle(595, 842));
                doc.addPage(page);
                PDImageXObject big = JPEGFactory.createFromImage(doc, picture(1600, 1200, i), 0.95f);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.drawImage(big, 0, 300, 595, 542);
                    stream.drawImage(shared, 40, 40, 220, 220);
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 16);
                    stream.newLineAtOffset(40, 270);
                    stream.showText("PAGE" + (i + 1));
                    stream.endText();
                }
            }
            doc.save(out);
        }
        System.out.println("make-images " + out + "：" + pages + " 页，每页一张 1600x1200 大图 + 一张共用图");
    }

    private static BufferedImage picture(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = Math.min(255, ((x * 3 + y + seed * 40) & 0xFF) + (x * 7 + y * 13) % 24);
                int g = (x ^ y ^ (seed * 13)) & 0xFF;
                int b = (y * 2 + x / 3 + seed * 7) & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /** 复制指定页。fix=true 时按 PDFBox 自己日志里的提示，把继承来的 Resources 补到导入页上。 */
    private static void copy(File source, File target, List<Integer> pages, boolean fix) throws Exception {
        try (PDDocument src = PDDocument.load(source); PDDocument dst = new PDDocument()) {
            for (int index : pages) {
                PDPage page = src.getPage(index);
                PDPage imported = dst.importPage(page);
                if (fix) {
                    PDResources resources = page.getResources();
                    if (resources != null) imported.setResources(resources);
                }
            }
            dst.save(target);
        }
        System.out.println("copy " + target + " 页=" + pages + " 补资源=" + fix);
    }

    /** 报页数、可提取文字、每页墨迹占比。墨迹 0% 就是用户看到的空白页。 */
    private static void check(File file) throws Exception {
        System.out.println("check " + file + " " + file.length() + " B");
        try (PDDocument doc = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            System.out.println("  页数=" + doc.getNumberOfPages()
                    + " 文字=[" + stripper.getText(doc).replaceAll("\\s+", " ").trim() + "]");
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 50);
                System.out.printf("  第%d页 %dx%d 有墨像素 %.2f%%%n", i + 1, image.getWidth(), image.getHeight(), ink(image));
            }
        }
    }

    /**
     * 压缩原型：遍历文档里所有间接对象，挑出图片流，按最长边降采样后重编 JPEG，
     * 再用 COSObject.setObject 原地换对象——全篇引用一次生效，共用的一张只处理一次。
     * 重编后反而变大的图保留原样；带 SMask、1 位掩膜、非 RGB/Gray 的跳过并说明原因。
     */
    private static void compress(File source, File target, int maxEdge, float quality) throws Exception {
        long before = source.length();
        long saved = 0;
        int replaced = 0;
        int keptBecauseBigger = 0;
        List<String> skipped = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(source)) {
            for (COSObject reference : imageObjects(doc)) {
                COSStream stream = (COSStream) reference.getObject();
                PDImageXObject image = new PDImageXObject(new PDStream(stream), null);
                String reason = skipReason(image);
                if (reason != null) {
                    skipped.add(image.getWidth() + "x" + image.getHeight() + " " + reason);
                    continue;
                }
                long original = stream.getLength();
                BufferedImage scaled = scale(image.getImage(), maxEdge);
                PDImageXObject fresh = JPEGFactory.createFromImage(doc, scaled, quality);
                long smaller = fresh.getCOSObject().getLength();
                if (smaller >= original) {
                    keptBecauseBigger++;
                    continue;
                }
                reference.setObject(fresh.getCOSObject());
                replaced++;
                saved += original - smaller;
            }
            doc.save(target);
        }
        System.out.printf("compress 最长边%d 质量%.2f：%d B -> %d B（占原 %.1f%%）%n",
                maxEdge, quality, before, target.length(), target.length() * 100.0 / before);
        System.out.printf("  换掉 %d 张（图片本身省 %d B），重编更大而保留 %d 张，跳过 %d 张%n",
                replaced, saved, keptBecauseBigger, skipped.size());
        for (String reason : skipped) System.out.println("  跳过：" + reason);
    }

    /** 和 PdfEngine.imageObjects 同一条逻辑：软掩膜本身也是图片对象，得先排掉再谈重编。 */
    private static List<COSObject> imageObjects(PDDocument doc) throws IOException {
        List<COSObject> found = new ArrayList<>();
        Set<COSBase> masks = new HashSet<>();
        for (COSObject reference : doc.getDocument().getObjects()) {
            COSBase base;
            try {
                base = reference.getObject();
            } catch (Exception error) {
                continue;
            }
            if (!(base instanceof COSStream stream)) continue;
            COSName subtype = stream.getDictionaryObject(COSName.SUBTYPE) instanceof COSName name ? name : null;
            if (subtype == null || !subtype.getName().equals(COSName.IMAGE.getName())) continue;
            found.add(reference);
            COSBase mask = stream.getItem(COSName.SMASK);
            if (mask != null) {
                masks.add(mask);
                if (mask instanceof COSObject object) masks.add(object.getObject());
            }
        }
        List<COSObject> kept = new ArrayList<>();
        for (COSObject reference : found) {
            if (masks.contains(reference) || masks.contains(reference.getObject())) continue;
            kept.add(reference);
        }
        return kept;
    }

    private static String skipReason(PDImageXObject image) throws IOException {
        COSDictionary dict = image.getCOSObject();
        if (dict.getItem(COSName.SMASK) != null) return "带透明通道，重编会丢";
        if (dict.getBoolean(COSName.IMAGE_MASK.getName(), false)) return "1 位掩膜图，重编会糊";
        if (dict.getInt(COSName.BITS_PER_COMPONENT, 8) == 1) return "1 位扫描图，重编会糊";
        PDColorSpace space = image.getColorSpace();
        String name = space == null ? "未知" : space.getName();
        if (!name.equals("DeviceRGB") && !name.equals("DeviceGray")) return "色彩空间 " + name + " 不重编";
        return null;
    }

    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        int edge = Math.max(source.getWidth(), source.getHeight());
        if (edge <= maxEdge) return source;
        double ratio = maxEdge / (double) edge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }
}
