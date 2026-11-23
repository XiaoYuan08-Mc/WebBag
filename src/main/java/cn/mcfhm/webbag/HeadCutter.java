package cn.mcfhm.webbag;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 把整张皮肤裁成 64×64 的头像（正面 + 帽子层）。不依赖服务端 API。 */
public final class HeadCutter {

    private HeadCutter() {
    }

    static byte[] crop(byte[] skinBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(skinBytes));
        if (img == null) {
            return null;
        }
        int width = img.getWidth();
        int height = img.getHeight();
        int scale = Math.max(1, width / 64);
        if (height < 16 * scale || width < 48 * scale) {
            return null;
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img.getSubimage(8 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 64, 64, null);
            g.drawImage(img.getSubimage(40 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 64, 64, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(out, "png", buffer);
        return buffer.toByteArray();
    }
}
