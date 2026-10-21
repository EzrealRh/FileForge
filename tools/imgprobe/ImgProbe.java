import com.fileforge.core.image.Flip;
import com.fileforge.core.image.ImageGeometry;
import com.fileforge.core.image.PixelRect;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 「改图片方向与边界」的桌面替身：**算**的那一步调的是 :core 里那份 ImageGeometry（应用真用的同一份），
 * 只有**画**的那一层用 Java2D 替安卓的 Bitmap + Matrix。
 *
 * 为什么要这么搭：判据要在装机之前就把"转 90° 之后到底是多大、裁的那块对不对"钉死，
 * 而桌面版 PDFBox 那次教训已经说明 —— 替身绕过产品那一步，判据再绿也不算那条路测过。
 * 所以这里刻意不自己写一套尺寸计算，plan 的全部内容都从 :core 拿，输出的宽高与说明都必须是它给的。
 *
 *   用法：./tools/imgprobe/run.sh transform <源图> <出图> <度数> <翻法0-3> <裁「l,t,w,h」或 -> <比例「16:9」或 ->
 *   出图格式按扩展名判（.jpg 会先把透明铺白，与应用那条一致）。
 */
public class ImgProbe {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException problem) {
            // 应用侧走的是同一条：把判层那句话原样递出去，而不是甩一摞栈
            System.out.println("没成 " + problem.getMessage());
            System.exit(1);
        } catch (Exception problem) {
            problem.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 6 || !args[0].equals("transform")) {
            System.err.println("用法：run.sh transform <源图> <出图> <度数> <翻法0-3> <裁|-> <比例|->");
            System.exit(2);
        }
        File source = new File(args[1]);
        File target = new File(args[2]);
        float degrees = Float.parseFloat(args[3]);
        Flip flip = flipOf(Integer.parseInt(args[4]));
        String crop = dashToNull(args[5]);
        String aspect = dashToNull(args[6]);

        BufferedImage sourceImage = ImageIO.read(source);
        if (sourceImage == null) {
            System.err.println("这份图 Java2D 打不开：" + source.getName());
            System.exit(1);
        }
        ImageGeometry.Plan plan = ImageGeometry.INSTANCE.plan(
                sourceImage.getWidth(), sourceImage.getHeight(), degrees, flip, crop, aspect);
        List<String> notes = plan.getNotes();

        BufferedImage base = crop == null && aspect == null
                ? toArgb(sourceImage)
                : cut(sourceImage, plan.getCrop());
        BufferedImage drawn = paint(base, plan);
        // 透明这件事的"要不要填白"先看画完那张有没有半透明像素（应用侧就是 `drawn.hasAlpha()` 这一句），
        // 再按输出容器落：PNG 原样留着，JPG 铺到白底上（Java2D 与安卓一样，不铺就是黑底）
        boolean translucent = hasTranslucent(drawn);
        String fallback = isJpeg(target) && translucent ? ImageGeometry.INSTANCE.transparentFallback(true) : null;
        if (isJpeg(target)) drawn = onWhite(drawn);

        ImageIO.write(drawn, isJpeg(target) ? "jpg" : "png", target);
        System.out.println("源 " + sourceImage.getWidth() + "x" + sourceImage.getHeight()
                + " 画出来 " + drawn.getWidth() + "x" + drawn.getHeight()
                + " 整倍数=" + plan.getQuarterTurn());
        for (String note : notes) System.out.println("说明 " + note);
        if (fallback != null) System.out.println("说明 " + fallback);
    }

    private static boolean hasTranslucent(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) return true;
            }
        }
        return false;
    }

    /** JPG 那条：把透明处铺到白底上（应用侧 flattenOnWhite 的同一件事）。 */
    private static BufferedImage onWhite(BufferedImage source) {
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return flat;
    }

    private static Flip flipOf(int mode) {
        switch (mode) {
            case 1: return Flip.Vertical;
            case 2: return Flip.Both;
            case 3: return Flip.None;
            default: return Flip.Horizontal;
        }
    }

    private static String dashToNull(String value) {
        return "-".equals(value) || value.isEmpty() ? null : value;
    }

    private static boolean isJpeg(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** 裁剪用 drawImage 的源矩形形式：不共享数据缓冲，免得后续旋转看到的是同一个 buffer。 */
    private static BufferedImage cut(BufferedImage source, PixelRect rect) {
        BufferedImage piece = new BufferedImage(rect.getWidth(), rect.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = piece.createGraphics();
        g.drawImage(source, 0, 0, rect.getWidth(), rect.getHeight(),
                rect.getLeft(), rect.getTop(), rect.getRight(), rect.getBottom(), null);
        g.dispose();
        return piece;
    }

    /** 镜像 → 旋转 → 平移进扩大的画布，顺序与应用的 drawPlan 同一条（矩阵也照同一串拼接次序写）。 */
    private static BufferedImage paint(BufferedImage base, ImageGeometry.Plan plan) {
        int width = plan.getOutWidth();
        int height = plan.getOutHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                plan.getQuarterTurn()
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int baseWidth = base.getWidth();
        int baseHeight = base.getHeight();
        float horizontal = plan.getFlip() == Flip.Horizontal || plan.getFlip() == Flip.Both ? -1f : 1f;
        float vertical = plan.getFlip() == Flip.Vertical || plan.getFlip() == Flip.Both ? -1f : 1f;
        AffineTransform at = new AffineTransform();
        at.translate((width - baseWidth) / 2f, (height - baseHeight) / 2f);
        at.rotate(Math.toRadians(plan.getDegrees()), baseWidth / 2f, baseHeight / 2f);
        at.translate((1 - horizontal) * baseWidth / 2f, (1 - vertical) * baseHeight / 2f);
        at.scale(horizontal, vertical);
        g.drawImage(base, at, null);
        g.dispose();
        return out;
    }
}
