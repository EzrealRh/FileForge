import com.fileforge.core.update.ReleaseAsset;
import com.fileforge.core.update.ReleaseFeed;
import com.fileforge.core.update.RemoteRelease;
import com.fileforge.core.update.SemanticVersion;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 应用内更新的验证台：跑的是 :core 里那份真代码，不是镜像实现。
 *
 * 用法：java -cp core.jar:kotlin-stdlib.jar UpdateProbe [token]
 * 覆盖四件事——① 带正确请求头能问到最新版本；② 没 token 时私有仓库的错误会说人要话；
 * ③ 选包选到 release 那个而不是 debug；④ 302 跳到 release-assets 域名后字节对得上。
 */
public class UpdateProbe {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String token = args.length > 0 && !args[0].isBlank() ? args[0] : null;
        System.out.println(token == null ? "不带 token（匿名）" : "带 token（仓库私有时需要）");

        String anonymous = fetch(ReleaseFeed.LATEST_URL, "application/vnd.github+json", null);
        RemoteRelease parsedAnon = null;
        try {
            parsedAnon = ReleaseFeed.INSTANCE.parse(anonymous);
            System.out.println("匿名也能读到发布页（仓库是公开的）");
        } catch (Exception error) {
            check("私有仓库的报错提到了 token", error.getMessage() != null && error.getMessage().contains("token"),
                "实际文案：" + error.getMessage());
        }

        if (token == null) {
            System.out.println("\n结论：需要 token 才能继续（仓库私有）。用法：bash run.sh <只读token>");
            System.exit(failed == 0 ? 2 : 1);
        }

        String body = fetch(ReleaseFeed.LATEST_URL, "application/vnd.github+json", token);
        RemoteRelease release = ReleaseFeed.INSTANCE.parse(body);
        System.out.println("最新版本 " + release.getTagName() + " · 资产 " + release.getAssets().size() + " 个");

        check("标签能读成版本号", release.getVersion() != null, "version=null");
        ReleaseAsset apk = release.pickApk();
        check("选出了 apk 包", apk != null, "pickApk 返回空");
        check("选的是 release 包不是 debug 包", apk != null && apk.getName().contains("release"),
            apk == null ? "无" : apk.getName());
        check("包体积读得出来", apk != null && apk.getSize() > 1_000_000L,
            apk == null ? "无" : String.valueOf(apk.getSize()));
        check("下载端点是 api 域名不是 github 域名",
            apk != null && apk.getApiDownloadUrl().startsWith("https://api.github.com/"),
            apk == null ? "无" : apk.getApiDownloadUrl());
        check("本地版本比最新版旧时判为有更新",
            release.isNewerThan(SemanticVersion.Companion.parse("0.0.1")), "isNewerThan 给了 false");
        check("本地已经是最新时不催更新",
            !release.isNewerThan(release.getVersion()), "同版本还说有更新");

        byte[] head = downloadHead(apk, token, 4096);
        check("302 跳完能拿到前 4KB 字节", head.length == 4096, "只拿到 " + head.length + " 字节");
        check("开头是 zip 的 PK 头（apk 就是 zip）", head[0] == 0x50 && head[1] == 0x4B,
            String.format("%02X %02X", head[0], head[1]));

        String localApk = System.getProperty("localApk");
        if (localApk != null && new File(localApk).isFile()) {
            byte[] local = Files.readAllBytes(Paths.get(localApk));
            boolean same = local.length >= head.length;
            for (int i = 0; i < head.length && same; i++) same = local[i] == head[i];
            check("线上包和本机构建产物前 4KB 一致", same, localApk);
            check("线上包体积与本机一致", local.length == apk.getSize(),
                "本地 " + local.length + " / API " + apk.getSize());
        }

        System.out.println("\n通过 " + passed + " 条，失败 " + failed + " 条");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String fetch(String endpoint, String accept, String token) throws Exception {
        HttpURLConnection connection = open(endpoint, accept, token);
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : new String(readAll(stream), java.nio.charset.StandardCharsets.UTF_8);
        connection.disconnect();
        if (text.isEmpty()) throw new IllegalStateException("HTTP " + status + " 且没有正文");
        return text;
    }

    /** 手动跟 302：这一步在安卓上也得这么做，所以在这里先验一遍。 */
    private static byte[] downloadHead(ReleaseAsset asset, String token, int bytes) throws Exception {
        HttpURLConnection connection = open(asset.getApiDownloadUrl(), "application/octet-stream", token);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Range", "bytes=0-" + (bytes - 1));
        int hops = 0;
        while (hops++ < 3) {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (status >= 300 && status < 400 && location != null && !location.isEmpty()) {
                connection.disconnect();
                connection = open(location, "application/octet-stream", null);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Range", "bytes=0-" + (bytes - 1));
                continue;
            }
            if (status != 200 && status != 206) throw new IllegalStateException("下载 HTTP " + status);
            byte[] got = readAll(connection.getInputStream());
            connection.disconnect();
            return got;
        }
        throw new IllegalStateException("302 跳了三次还没到");
    }

    private static HttpURLConnection open(String endpoint, String accept, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "FileForge-Android");
        connection.setRequestProperty("Accept", accept);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        return input.readAllBytes();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + " —— " + detail);
        }
    }
}
