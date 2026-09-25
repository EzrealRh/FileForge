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
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.fileforge.core.pdf.TextLayout;
import com.fileforge.core.pdf.TextLayoutPlanner;
import com.fileforge.core.pdf.TextLine;
import com.fileforge.core.pdf.TextPage;
import kotlin.jvm.functions.Function2;

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
            case "stamp" -> verifyStamping(new File(args[1]));
            case "security" -> verifySecurity(new File(args[1]));
            case "textlayout" -> verifyTextLayout(new File(args[1]), new File(args[2]));
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
            if (!problems.isEmpty()) throw new AssertionError(file.getName() + " 结构不合格：" + problems.get(0));
            if (verbose) System.out.println("  结构检查通过");
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
        verifyStamping(dir);
        System.out.println("verify PASS");
    }

    /**
     * 加密这件事，界面文案全靠猜是会骗人的：所有者密码留空时库会不会自己编一个、
     * 用户密码够不够把保护去掉、勾了"禁止复制"之后自家"提取文字"还剩不剩得下东西 ——
     * 这四条都直接决定界面上该有几个输入框、该说什么话。所以先量。
     */
    private static void verifySecurity(File dir) throws Exception {
        dir.mkdirs();
        File plain = new File(dir, "sec-plain.pdf");
        makeInherited(plain);
        expect(textOf(plain).contains("MARKER1"), "加密前的对照文件要抽得出字");

        File locked = new File(dir, "sec-locked.pdf");
        protectTo(plain, locked, "owner-pw", "user-pw", true, 128, true);
        // 加密后的文件连"不带密码读一遍"都进不去，所以结构体检只能等解除保护之后再做
        boolean blocked;
        try (PDDocument ignored = PDDocument.load(locked)) {
            blocked = false;
        } catch (InvalidPasswordException error) {
            blocked = true;
        }
        expect(blocked, "不设密码直接打开必须被拒（InvalidPasswordException）");

        boolean userOpens = false;
        boolean ownerOpens = false;
        String extractUnderRestriction = "没跑到";
        try (PDDocument doc = PDDocument.load(locked, "user-pw")) {
            userOpens = true;
            extractUnderRestriction = runStripper(doc);
        } catch (Exception error) {
            extractUnderRestriction = "抛 " + error.getClass().getSimpleName();
        }
        try (PDDocument doc = PDDocument.load(locked, "owner-pw")) {
            ownerOpens = doc.isEncrypted();
        } catch (Exception error) {
            ownerOpens = false;
        }
        System.out.println("  用户密码能开=" + userOpens + " 所有者密码能开=" + ownerOpens
                + " 限制复制下抽文字：" + describeExtract(extractUnderRestriction));
        expect(userOpens, "用户密码要能打开");
        expect(ownerOpens, "所有者密码也要能打开");

        // 只给用户密码能不能去掉保护 —— 决定界面上要不要逼用户记住所有者密码
        File unlocked = new File(dir, "sec-unlocked.pdf");
        boolean removedWithUserPw = runCatching(() -> {
            try (PDDocument doc = PDDocument.load(locked, "user-pw")) {
                doc.setAllSecurityToBeRemoved(true);
                doc.save(unlocked);
            }
        });
        System.out.println("  只用用户密码能否解除保护：" + removedWithUserPw);
        if (removedWithUserPw) {
            try (PDDocument doc = PDDocument.load(unlocked)) {
                expect(!doc.isEncrypted(), "解除后不该再标着已加密");
                expect(textOf(unlocked).contains("MARKER1"), "解除保护不能把文字弄丢");
            }
        }

        // 所有者密码留空：库会不会随机造一个（造了就等于用户自己再也解不开）
        File blankOwner = new File(dir, "sec-blank-owner.pdf");
        boolean blankOk = runCatching(() -> protectTo(plain, blankOwner, "", "user-pw", true, 128, true));
        String blankOpensWith = "打不开";
        if (blankOk) {
            for (String guess : new String[]{"", "user-pw"}) {
                try (PDDocument doc = PDDocument.load(blankOwner, guess)) {
                    blankOpensWith = "密码「" + guess + "」能开";
                    break;
                } catch (Exception ignored) {
                    // 换下一个
                }
            }
        }
        System.out.println("  所有者密码留空：加密" + (blankOk ? "成功" : "失败") + "，" + blankOpensWith);
        expect(blankOk && blankOpensWith.contains("user-pw"),
                "所有者密码留空时用户密码仍要能打开口袋（否则一个密码框就是锁死自己的陷阱）");

        // 留空所有者密码会留下含糊状态，所以实现里的做法是"两个都填同一个"。这条路必须验一遍
        File samePw = new File(dir, "sec-same-pw.pdf");
        protectTo(plain, samePw, "solo-pw", "solo-pw", true, 128, true);
        boolean unlockSame = runCatching(() -> {
            try (PDDocument doc = PDDocument.load(samePw, "solo-pw")) {
                doc.setAllSecurityToBeRemoved(true);
                doc.save(new File(dir, "sec-same-unlocked.pdf"));
            }
        });
        expect(unlockSame, "只填一个密码（所有者=用户）时必须能自己解开");
        strict(new File(dir, "sec-same-unlocked.pdf"), true);

        // 权限位在 PDFBox 2.0.27 里是"给阅读器看的建议"，不拦自家抽取 —— 界面文案据此写。
        // 这条断言是为了哪天库改成强制时立刻发现，好去改话术
        try (PDDocument doc = PDDocument.load(locked, "user-pw")) {
            expect(runStripper(doc).contains("MARKER1"),
                    "PDFBox 当前不强制「禁止复制」权限位（界面如实这么写；这条翻了就说明库开始拦了）");
        }

        // AES 与位长的约束
        String smallKey = "128 位 AES 通过";
        try {
            protectTo(plain, new File(dir, "sec-aes-40.pdf"), "owner-pw", "user-pw", false, 40, true);
        } catch (Exception error) {
            smallKey = "40 位 + AES 被拒：" + error.getClass().getSimpleName();
        }
        String rc4 = "40 位 RC4 通过";
        try {
            protectTo(plain, new File(dir, "sec-rc4-40.pdf"), "owner-pw", "user-pw", false, 40, false);
        } catch (Exception error) {
            rc4 = "40 位 RC4 被拒：" + error.getClass().getSimpleName();
        }
        System.out.println("  " + smallKey + "；" + rc4);
        System.out.println("security 量完");
    }

    private static String describeExtract(String outcome) {
        if (outcome.startsWith("抛 ")) return outcome;
        return outcome.trim().isEmpty() ? "抽出空（被权限挡住）" : "仍抽得出字";
    }

    private static String runStripper(PDDocument doc) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(doc);
    }

    private static boolean runCatching(ThrowingRunnable block) {
        try {
            block.run();
            return true;
        } catch (Exception error) {
            System.out.println("  （失败原因 " + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()).trim() + "）");
            return false;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 和安卓侧要写的完全同一套 API 调用，只是换成桌面版类名前缀。 */
    private static void protectTo(File source, File target, String owner, String user,
                                  boolean restrict, int keyLength, boolean aes) throws Exception {
        AccessPermission permission = new AccessPermission();
        if (restrict) {
            permission.setCanPrint(false);
            permission.setCanExtractContent(false);
            permission.setCanModify(false);
            permission.setCanModifyAnnotations(false);
            permission.setCanFillInForm(false);
        }
        StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, permission);
        policy.setEncryptionKeyLength(keyLength);
        policy.setPreferAES(aes);
        try (PDDocument doc = PDDocument.load(source)) {
            doc.protect(policy);
            doc.save(target);
        }
    }

    /** 探针专用的断言：说明在前、条件在后，读起来像句子。与 expect 同义，只是参数顺序反。 */
    private static void report(String what, boolean ok) {
        expect(ok, what);
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

    /**
     * 文本 → PDF 的宽度模型。
     *
     * core 里的 TextLayoutPlanner 信的是"字体报的宽度就是画出来占的宽度"，这一条单测测不到 ——
     * 它手里只有假尺子。这里把真尺子（PDType0Font.getStringWidth）接回**同一个**排版器（编译
     * :core:jar 进 classpath，不重写第二套断行规则），画成 PDF 再渲染成图，数墨迹的边界。
     *
     * 两个方向都要查：宽度报少了字会印到边距外，报多了每行提前断、纸面右边豁口。所以除了"不出血"，
     * 还有一条"最长行必须接近栏宽" —— 否则把字号调到 1pt 也能顺利通过溢出检查。
     */
    private static void verifyTextLayout(File fontFile, File out) throws Exception {
        StringBuilder text = new StringBuilder();
        String cjk = "文件工坊把图片、文档、音频都放在手机上处理，一个字都不上传；断行要能在字之间断，收尾标点不许顶到行首。";
        String latin = "The quick brown fox jumps over the lazy dog while converting pdf images and fonts. ";
        for (int p = 0; p < 60; p++) {
            text.append(cjk).append(latin).append(cjk).append((char) 10);
            if (p % 4 == 3) text.append((char) 10);
        }
        String body = text.toString();

        float pageW = 595f, pageH = 842f, margin = 56f, size = 11f;
        float indent = size * 2f;
        float content = pageW - margin * 2;
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(
                doc, java.nio.file.Files.newInputStream(fontFile.toPath()), true);
            Function2<String, Float, Float> widthOf = (chunk, pt) -> {
                try {
                    return font.getStringWidth(chunk) / 1000f * pt;
                } catch (IOException e) {
                    throw new IllegalStateException("这个字体量不出「" + chunk + "」", e);
                }
            };
            TextLayout plan = TextLayoutPlanner.INSTANCE.layout(
                body, pageW, pageH, margin, size, 1.4f, indent, widthOf);

            int drawn = 0;
            float modelRightMost = 0f;
            for (TextPage page : plan.getPages()) {
                PDPage pdPage = new PDPage(new PDRectangle(pageW, pageH));
                doc.addPage(pdPage);
                PDPageContentStream stream = new PDPageContentStream(doc, pdPage);
                stream.beginText();
                stream.setFont(font, size);
                stream.setNonStrokingColor(0f, 0f, 0f);
                for (TextLine line : page.getLines()) {
                    if (line.getText().isEmpty()) continue;
                    stream.setTextMatrix(Matrix.getTranslateInstance(line.getX(), pageH - line.getY()));
                    stream.showText(line.getText());
                    drawn++;
                    modelRightMost = Math.max(modelRightMost, line.getX() + widthOf.invoke(line.getText(), size));
                }
                stream.endText();
                stream.close();
            }
            doc.save(out);

            report("排出了多页而不是挤成一页（页数=" + plan.getPageCount() + "）", plan.getPageCount() > 3);
            report("每行都画了上去（" + drawn + " 行）", drawn > 100);
            report("排版器认为没有一行装不下（notes=" + plan.getNotes() + "）", plan.getNotes().isEmpty());
            double dpr = 150 / 72.0;
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                int[] box = bounds(renderer.renderImageWithDPI(i, 150));
                double left = box[0] / dpr, right = box[2] / dpr, top = box[1] / dpr, bottom = box[3] / dpr;
                report("第 " + (i + 1) + " 页左侧不出血（inkLeft=" + pt(left) + "）", left >= margin - 2.5);
                report("第 " + (i + 1) + " 页右侧不越边距（inkRight=" + pt(right) + "，上限 " + pt(content + margin) + "）",
                    content + margin + 2.5 >= right);
                report("第 " + (i + 1) + " 页顶部不越边距（inkTop=" + pt(top) + "）", top >= margin - 6);
                report("第 " + (i + 1) + " 页底部不越边距（inkBottom=" + pt(bottom) + "）", bottom <= pageH - margin + 6);
            }
            report("最长行用掉了栏宽的大部分（模型右端=" + pt(modelRightMost) + "，栏尾=" + pt(margin + content) + "）",
                modelRightMost >= margin + content - size * 2.5);
            String extracted = nonWhitespace(textOf(out, false));
            String wanted = nonWhitespace(body);
            report("文字层抽回来的字数与源文本相当（抽出 " + extracted.length() + " / 应有 " + wanted.length() + "）",
                wanted.length() * 0.98 <= extracted.length());
        }
    }

    /** 墨迹包围盒：{minX, minY, maxX, maxY}，全白图返回全 0。 */
    private static int[] bounds(BufferedImage image) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                boolean dark = ((rgb >> 16) & 0xFF) < 245 || ((rgb >> 8) & 0xFF) < 245 || (rgb & 0xFF) < 245;
                if (!dark) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return new int[]{0, 0, 0, 0};
        return new int[]{minX, minY, maxX, maxY};
    }

    private static String pt(double value) { return String.format("%.1f", value); }

    /** 把所有空白剔掉：比"抽回来多少字"时两边口径要一致，也不靠正则转义。 */
    private static String nonWhitespace(String value) {
        StringBuilder out = new StringBuilder();
        value.chars().filter(c -> !Character.isWhitespace(c)).forEach(out::appendCodePoint);
        return out.toString();
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
        return textOf(file, true);
    }

    /**
     * 斜着排的水印按位置排序会被打乱（"内部资料"抽成"料料资资部部内内"），
     * 所以要看书写顺序就把 sortByPosition 关掉——两种都要能抽出来才算字体内嵌没问题。
     */
    private static String textOf(File file, boolean byPosition) throws Exception {
        try (PDDocument doc = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(byPosition);
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

    // ---- 页码 / 水印：CoreLogic 里 PageStampPlan + StampFrame 的 Java 镜像，用渲染结果实测 ----

    /** 只有左上角一个标记的页面：别处出现墨迹，就只能是我们盖上去的东西。第 2 页带 /Rotate 90。 */
    private static void makeBlankMarked(File out, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(new PDRectangle(595, 842));
                if (i == 1) page.setRotation(90);
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 16);
                    stream.setTextMatrix(Matrix.getTranslateInstance(40, 790));
                    stream.showText("MARK" + (i + 1));
                    stream.endText();
                }
            }
            doc.save(out);
        }
    }

    /** {平移x, 平移y, 反旋转度数, 视觉宽, 视觉高}，和 Kotlin 的 StampFrame.forRotation 一致。 */
    private static float[] frameFor(float boxWidth, float boxHeight, int rotation) {
        int turn = ((rotation % 360) + 360) % 360;
        return switch (turn) {
            case 90 -> new float[]{boxWidth, 0f, 90f, boxHeight, boxWidth};
            case 180 -> new float[]{boxWidth, boxHeight, 180f, boxWidth, boxHeight};
            case 270 -> new float[]{0f, boxHeight, -90f, boxHeight, boxWidth};
            default -> new float[]{0f, 0f, 0f, boxWidth, boxHeight};
        };
    }

    private static void applyFrame(PDPageContentStream stream, float[] frame) throws IOException {
        Matrix matrix = new Matrix();
        matrix.translate(frame[0], frame[1]);
        matrix.rotate(Math.toRadians(frame[2]));
        stream.transform(matrix);
    }

    private static String numberText(int number, int total, int style) {
        return switch (style) {
            case 1 -> "第 " + number + " 页";
            case 2 -> number + " / " + total;
            default -> String.valueOf(number);
        };
    }

    /** 加页码：只追加内容流，坐标按"你看到的底部中间"算。 */
    private static void stampNumbers(File source, File target, int style, float margin, float size, int first) throws Exception {
        try (PDDocument doc = PDDocument.load(source)) {
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getMediaBox();
                float[] frame = frameFor(box.getWidth(), box.getHeight(), page.getRotation());
                String text = numberText(first + i, total, style);
                float width = PDType1Font.HELVETICA.getStringWidth(text) / 1000f * size;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    applyFrame(stream, frame);
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, size);
                    stream.setNonStrokingColor(0.1f);
                    stream.setTextMatrix(Matrix.getTranslateInstance(frame[3] / 2f - width / 2f, margin + size * 0.25f));
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(target);
        }
    }

    /** 水印：中文字体走子集内嵌，透明度用图形状态，平铺按行列取格子中心。 */
    private static void stampWatermark(File source, File target, String text, int columns, int rows,
                                       float alpha, float tilt, File fontFile) throws Exception {
        try (PDDocument doc = PDDocument.load(source)) {
            TrueTypeFont ttf = new TTFParser().parse(fontFile.getPath());
            PDFont font = PDType0Font.load(doc, ttf, true);
            PDExtendedGraphicsState fade = new PDExtendedGraphicsState();
            fade.setNonStrokingAlphaConstant(alpha);
            double units = 0;
            for (char c : text.toCharArray()) units += c >= 0x2E80 ? 1.0 : 0.55;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getMediaBox();
                float[] frame = frameFor(box.getWidth(), box.getHeight(), page.getRotation());
                float size = (float) Math.min(160, Math.max(6, frame[3] / columns * 0.9 / units));
                float width = font.getStringWidth(text) / 1000f * size;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    stream.setGraphicsStateParameters(fade);
                    applyFrame(stream, frame);
                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < columns; col++) {
                            float cx = frame[3] * (col + 0.5f) / columns;
                            float cy = frame[4] * (row + 0.5f) / rows;
                            Matrix shift = new Matrix();
                            shift.translate(cx - (float) Math.cos(Math.toRadians(tilt)) * width / 2f,
                                    cy - (float) Math.sin(Math.toRadians(tilt)) * width / 2f);
                            shift.rotate(Math.toRadians(tilt));
                            stream.beginText();
                            stream.setFont(font, size);
                            stream.setNonStrokingColor(0.3f);
                            stream.setTextMatrix(shift);
                            stream.showText(text);
                            stream.endText();
                        }
                    }
                }
            }
            doc.save(target);
        }
    }

    /** 只看渲染图里某个分块区域的墨迹占比（分数坐标，y 从渲染图顶部算）。 */
    private static double bandInk(File pdf, int page, double yFrom, double yTo, double xFrom, double xTo) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, 50);
            int x0 = (int) (image.getWidth() * xFrom);
            int x1 = Math.min(image.getWidth(), (int) Math.ceil(image.getWidth() * xTo));
            int y0 = (int) (image.getHeight() * yFrom);
            int y1 = Math.min(image.getHeight(), (int) Math.ceil(image.getHeight() * yTo));
            long dark = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 0xFF) < 245 || ((rgb >> 8) & 0xFF) < 245 || (rgb & 0xFF) < 245) dark++;
                }
            }
            long area = (long) Math.max(1, y1 - y0) * Math.max(1, x1 - x0);
            return dark * 100.0 / area;
        }
    }

    private static int[] renderedSize(File pdf, int page) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, 50);
            return new int[]{image.getWidth(), image.getHeight()};
        }
    }

    private static int countOf(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) found++;
        return found;
    }

    /** 页码与水印的实测：位置、文字层、旋转页、透明度、中文字体子集，全部用渲染图和抽取结果判定。 */
    private static void verifyStamping(File dir) throws Exception {
        dir.mkdirs();
        File font = new File(System.getProperty("probe.font", "C:/Windows/Fonts/simhei.ttf"));
        File blank = new File(dir, "blank.pdf");
        makeBlankMarked(blank, 3);

        int[] straight = renderedSize(blank, 0);
        int[] turned = renderedSize(blank, 1);
        expect(straight[0] < straight[1], "没旋转的页渲染出来是竖的（" + straight[0] + "x" + straight[1] + "）");
        expect(turned[0] > turned[1], "带 /Rotate 90 的页渲染出来要换成横的（" + turned[0] + "x" + turned[1] + "）");
        for (int i = 0; i < 3; i++) {
            expect(bandInk(blank, i, 0.90, 1.0, 0.0, 1.0) == 0, "盖章前页面底部不该有任何墨迹（第" + (i + 1) + "页）");
        }

        File numbered = new File(dir, "numbered.pdf");
        stampNumbers(blank, numbered, 2, 20f, 11f, 1);
        String numbers = textOf(numbered);
        expect(numbers.contains("1 / 3") && numbers.contains("2 / 3") && numbers.contains("3 / 3"),
                "页码要进文字层，抽取结果：" + numbers);
        for (int i = 0; i < 3; i++) {
            double bottom = bandInk(numbered, i, 0.90, 1.0, 0.0, 1.0);
            expect(bottom > 0.02, "第" + (i + 1) + "页底部该有页码墨迹，实测 " + bottom + "%");
        }
        // 旋转页也按"看到的方向"落位：它的渲染图是横的，底部照样要有墨迹
        expect(bandInk(numbered, 1, 0.90, 1.0, 0.0, 1.0) > 0.02, "旋转 90° 那页的页码也要打在视觉底部");
        expect(bandInk(numbered, 0, 0.0, 0.10, 0.0, 0.35) == bandInk(blank, 0, 0.0, 0.10, 0.0, 0.35),
                "左上角原来的标记不许被动到");
        try (PDDocument doc = PDDocument.load(numbered)) {
            expect(doc.getNumberOfPages() == 3, "加页码不改页数");
        }
        strict(numbered, true);

        if (font.isFile()) {
            long before = blank.length();
            File marked = new File(dir, "watermarked.pdf");
            stampWatermark(blank, marked, "内部资料", 2, 2, 0.18f, 45f, font);
            String text = textOf(marked, false).replace(" ", "");
            expect(text.contains("内部资料"), "中文水印要按书写顺序抽得出来（抽出来是：" + text + "）");
            expect(countOf(text, "内部资料") >= 4, "2x2 平铺该有 4 处水印，实测 " + countOf(text, "内部资料"));
            expect(bandInk(blank, 0, 0.35, 0.65, 0.1, 0.9) == 0, "水印测试页中部本来是空的");
            double faint = bandInk(marked, 0, 0.30, 0.70, 0.05, 0.95);
            expect(faint > 0.02, "中部该出现水印墨迹，实测 " + faint + "%");

            // 挑字体的判据：encode 得过去才算这字体能显示，拉丁字体必须在中文面前露馅
            File latin = new File(System.getProperty("probe.latin", "C:/Windows/Fonts/arial.ttf"));
            if (latin.isFile()) {
                try (PDDocument probe = new PDDocument()) {
                    PDFont arial = PDType0Font.load(probe, new TTFParser().parse(latin.getPath()), true);
                    boolean latinFails = false;
                    try {
                        arial.encode("内部资料");
                    } catch (Exception error) {
                        latinFails = true;
                    }
                    expect(latinFails, "拉丁字体要在中文字面前 encode 失败，否则挑字体的判据不可信");
                    boolean latinOk = true;
                    try {
                        arial.encode("Draft");
                    } catch (Exception error) {
                        latinOk = false;
                    }
                    expect(latinOk, "同一个字体要能编码拉丁字母");
                }
            }
            File solid = new File(dir, "watermark-solid.pdf");
            stampWatermark(blank, solid, "内部资料", 2, 2, 1f, 45f, font);
            double opaque = bandInk(solid, 0, 0.30, 0.70, 0.05, 0.95);
            expect(faint < opaque, "透明度真生效：淡的 " + faint + "% 该比实的 " + opaque + "% 浅");
            expect(bandInk(marked, 1, 0.30, 0.70, 0.05, 0.95) > 0.02, "旋转页也要盖到水印");
            strict(marked, true);
            long growth = marked.length() - before;
            expect(growth < 600_000, "中文字体必须子集内嵌（整份 " + (font.length() / 1024) + "KB，实际只长了 " + (growth / 1024) + "KB）");
            System.out.printf("  中文字体子集内嵌：%dB → %dB（+%dB），源字体 %dKB%n", before, marked.length(), growth, font.length() / 1024);
        } else {
            expect(false, "找不到中文字体 " + font + "，水印那半没验");
        }
        System.out.println("stamp PASS");
    }
}
