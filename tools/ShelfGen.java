import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Renders a phone-camera view of a supermarket shelf edge for README screenshots: product bottoms
 * above (out of focus), the price rail with a big Georgian tag in focus, and the next shelf below
 * in shadow. Writes each scene plus a crop of its tag to scan (see PROGRESS.md, "Screenshots").
 * Needs macOS fonts (Arial Unicode for Georgian). Usage: java tools/ShelfGen.java <outDir>
 */
public class ShelfGen {
    static final int W = 1080, H = 2340;
    static final int RAIL_TOP = 560, RAIL_BOTTOM = 1080;
    static Font georgian, digits;

    public static void main(String[] args) throws Exception {
        String out = args[0];
        georgian = Font.createFont(Font.TRUETYPE_FONT, new File("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"));
        digits = new Font("Helvetica Neue", Font.BOLD, 10);

        scene(out + "/shelf_juice.png", out + "/tag_juice.png", new Random(7), new Color[]{
            new Color(0xF28C28), new Color(0xE9C22B), new Color(0xD8432E), new Color(0x7DB343), new Color(0xF4A340), new Color(0xB5332E),
        }, g -> {
            // Orange juice, 1 litre: name, big price with the lari sign, price per litre, barcode.
            tagBase(g, new Color(0x2E7D32));
            text(g, georgian.deriveFont(Font.PLAIN, 58f), Color.BLACK, "ფორთოხლის წვენი", 36, 120);
            text(g, georgian.deriveFont(Font.PLAIN, 42f), new Color(0x333333), "1 ლ", 36, 178);
            text(g, digits.deriveFont(Font.BOLD, 170f), Color.BLACK, "4.49", 150, 340);
            text(g, digits.deriveFont(Font.BOLD, 96f), Color.BLACK, "₾", 545, 338);
            text(g, georgian.deriveFont(Font.PLAIN, 26f), new Color(0x444444), "1 ლ-ის ფასი: 4.49", 36, 392);
            text(g, digits.deriveFont(Font.BOLD, 24f), new Color(0x444444), "₾", 262, 392);
            barcode(g, 470, 362, 200, 34, "4860012345671");
        });

        scene(out + "/shelf_pasta.png", out + "/tag_pasta.png", new Random(11), new Color[]{
            new Color(0x1E4E9C), new Color(0xF2C230), new Color(0x1E4E9C), new Color(0xC62828), new Color(0x2F6DB5), new Color(0xE7A928),
        }, g -> {
            // Spaghetti, 500 g, with a "from 3 pieces" deal band.
            tagBase(g, new Color(0x1565C0));
            text(g, georgian.deriveFont(Font.PLAIN, 44f), Color.BLACK, "სპაგეტი 500გ", 36, 112);
            text(g, digits.deriveFont(Font.BOLD, 132f), Color.BLACK, "5.49", 190, 250);
            text(g, digits.deriveFont(Font.BOLD, 76f), Color.BLACK, "₾", 500, 248);
            g.setColor(new Color(0xFFD54F));
            g.fillRect(0, 282, 740, 158);
            text(g, georgian.deriveFont(Font.PLAIN, 34f), Color.BLACK, "3 ცალის ყიდვისას", 36, 330);
            text(g, digits.deriveFont(Font.BOLD, 84f), Color.BLACK, "4.49", 36, 420);
            text(g, digits.deriveFont(Font.BOLD, 50f), Color.BLACK, "₾", 236, 418);
            text(g, georgian.deriveFont(Font.PLAIN, 30f), Color.BLACK, "/ ცალი", 290, 418);
        });
    }

    interface TagPainter { void paint(Graphics2D g); }

    static void scene(String scenePath, String tagPath, Random rnd, Color[] palette, TagPainter tag) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gfx(img);

        // Back of the shelf above, in warm store light.
        g.setPaint(new GradientPaint(0, 0, new Color(0x6E655C), 0, RAIL_TOP, new Color(0x3A342F)));
        g.fillRect(0, 0, W, RAIL_TOP);
        // Bottoms of the products standing on that shelf.
        int x = -60;
        int i = 0;
        while (x < W + 40) {
            int w = 230 + rnd.nextInt(110);
            Color c = palette[i++ % palette.length];
            if (rnd.nextBoolean()) bottle(g, x, -200, w, RAIL_TOP + 200 - 8, c, rnd); else carton(g, x, -200, w, RAIL_TOP + 200 - 8, c, rnd);
            x += w + 14 + rnd.nextInt(18);
        }
        // Lower shelf: set back and in the shadow of the shelf above.
        g.setPaint(new GradientPaint(0, RAIL_BOTTOM, new Color(0x16130F), 0, H, new Color(0x2B2620)));
        g.fillRect(0, RAIL_BOTTOM, W, H - RAIL_BOTTOM);
        x = -30;
        while (x < W + 40) {
            int w = 200 + rnd.nextInt(90);
            Color c = darker(palette[i++ % palette.length], 0.55f);
            int top = RAIL_BOTTOM + 180 + rnd.nextInt(90);
            if (rnd.nextBoolean()) bottle(g, x, top, w, H - top + 200, c, rnd); else carton(g, x, top, w, H - top + 200, c, rnd);
            x += w + 18 + rnd.nextInt(20);
        }
        // Shadow cast by the shelf board onto the lower shelf.
        g.setPaint(new GradientPaint(0, RAIL_BOTTOM, new Color(0, 0, 0, 200), 0, RAIL_BOTTOM + 260, new Color(0, 0, 0, 0)));
        g.fillRect(0, RAIL_BOTTOM, W, 260);
        g.dispose();

        // Everything but the rail is out of focus.
        boxBlur(img, 9, 3);

        g = gfx(img);
        rail(g);
        // Neighbouring tags, cut off by the frame.
        BufferedImage neighbour = new BufferedImage(740, 450, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ng = gfx(neighbour);
        tagBase(ng, new Color(0x9E9E9E));
        text(ng, digits.deriveFont(Font.BOLD, 150f), Color.BLACK, "3.19", 180, 320);
        ng.dispose();
        g.drawImage(neighbour, -640, RAIL_TOP + 40, null);
        g.drawImage(neighbour, 990, RAIL_TOP + 40, null);
        // The tag being scanned.
        BufferedImage tagImg = new BufferedImage(740, 450, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = gfx(tagImg);
        tag.paint(tg);
        tg.dispose();
        int tx = (W - 740) / 2, ty = RAIL_TOP + 40;
        g.setColor(new Color(0, 0, 0, 70));
        g.fillRoundRect(tx + 6, ty + 10, 740, 450, 18, 18);
        g.drawImage(tagImg, tx, ty, null);
        // The rail's clear plastic cover catches the light.
        g.setPaint(new GradientPaint(0, RAIL_TOP + 30, new Color(255, 255, 255, 60), 0, RAIL_TOP + 140, new Color(255, 255, 255, 0)));
        g.fillRect(0, RAIL_TOP + 30, W, 110);
        g.dispose();

        finish(img, rnd);
        ImageIO.write(img, "png", new File(scenePath));
        // The tag as the phone would photograph it, for scanning.
        BufferedImage crop = img.getSubimage(tx - 60, ty - 60, 740 + 120, 450 + 120);
        BufferedImage copy = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(crop, 0, 0, null);
        ImageIO.write(copy, "png", new File(tagPath));
        System.out.println("wrote " + scenePath + " and " + tagPath);
    }

    static Graphics2D gfx(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static void tagBase(Graphics2D g, Color band) {
        g.setColor(new Color(0xFBFBF7));
        g.fillRoundRect(0, 0, 740, 450, 18, 18);
        g.setColor(band);
        g.fillRect(0, 0, 740, 16);
    }

    static void text(Graphics2D g, Font f, Color c, String s, int x, int y) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static void barcode(Graphics2D g, int x, int y, int w, int h, String digitsText) {
        Random r = new Random(digitsText.hashCode());
        g.setColor(Color.BLACK);
        int cx = x;
        while (cx < x + w) {
            int bw = 1 + r.nextInt(4);
            if (r.nextBoolean()) g.fillRect(cx, y, bw, h);
            cx += bw + 1;
        }
    }

    /** The price rail: a pale metal strip with lips top and bottom. */
    static void rail(Graphics2D g) {
        g.setPaint(new GradientPaint(0, RAIL_TOP, new Color(0xD9DCDF), 0, RAIL_BOTTOM, new Color(0xB8BCC0)));
        g.fillRect(0, RAIL_TOP, W, RAIL_BOTTOM - RAIL_TOP);
        g.setColor(new Color(0x8E9398));
        g.fillRect(0, RAIL_TOP, W, 14);
        g.fillRect(0, RAIL_BOTTOM - 16, W, 16);
        g.setColor(new Color(0xF2F3F4));
        g.fillRect(0, RAIL_TOP + 14, W, 6);
    }

    /** A plastic bottle's lower half: rounded body, label, cylindrical shading. */
    static void bottle(Graphics2D g, int x, int y, int w, int h, Color c, Random rnd) {
        Shape body = new RoundRectangle2D.Float(x, y, w, h, w * 0.5f, w * 0.35f);
        g.setPaint(cylinder(x, w, c));
        g.fill(body);
        // Label band with a round emblem and a few lines of print.
        int ly = y + (int) (h * 0.35);
        int lh = (int) (h * 0.38);
        g.setPaint(cylinder(x, w, blend(c, Color.WHITE, 0.75f)));
        g.fill(new Rectangle2D.Float(x, ly, w, lh));
        g.setColor(c);
        g.fillOval(x + w / 2 - w / 5, ly + lh / 6, w * 2 / 5, w * 2 / 5);
        g.setColor(darker(c, 0.6f));
        for (int k = 0; k < 3; k++) g.fillRect(x + w / 5, ly + lh / 6 + w * 2 / 5 + 24 + k * 22, w * 3 / 5 - rnd.nextInt(w / 4), 8);
        highlight(g, x, y, w, h);
    }

    /** A drinks carton's lower half: flat front with a fruit band and a side in shadow. */
    static void carton(Graphics2D g, int x, int y, int w, int h, Color c, Random rnd) {
        int side = w / 6;
        g.setColor(darker(c, 0.55f));
        g.fillRect(x + w - side, y, side, h);
        g.setPaint(new GradientPaint(x, 0, blend(c, Color.WHITE, 0.15f), x + w - side, 0, darker(c, 0.85f)));
        g.fillRect(x, y, w - side, h);
        int by = y + (int) (h * 0.45);
        g.setColor(blend(c, Color.WHITE, 0.8f));
        g.fillRect(x, by, w - side, (int) (h * 0.22));
        g.setColor(darker(c, 0.7f));
        g.fillOval(x + (w - side) / 2 - w / 7, by + 20, w * 2 / 7, w * 2 / 7);
        g.setColor(new Color(255, 255, 255, 170));
        for (int k = 0; k < 2; k++) g.fillRect(x + 24, by - 60 - k * 30, (w - side) - 60 - rnd.nextInt(60), 10);
        highlight(g, x, y, w - side, h);
    }

    static void highlight(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(x + w * 0.2f, 0, new Color(255, 255, 255, 70), x + w * 0.35f, 0, new Color(255, 255, 255, 0)));
        g.fillRect(x + (int) (w * 0.15f), y, (int) (w * 0.25f), h);
    }

    static Paint cylinder(int x, int w, Color c) {
        return new LinearGradientPaint(x, 0, x + w, 0, new float[]{0f, 0.3f, 0.55f, 1f},
            new Color[]{darker(c, 0.5f), blend(c, Color.WHITE, 0.12f), c, darker(c, 0.45f)});
    }

    static Color darker(Color c, float f) {
        return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f));
    }

    static Color blend(Color a, Color b, float t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Three box blurs approximate a Gaussian. */
    static void boxBlur(BufferedImage img, int r, int passes) {
        int w = img.getWidth(), h = img.getHeight();
        int[] a = img.getRGB(0, 0, w, h, null, 0, w), b = new int[a.length];
        for (int p = 0; p < passes; p++) {
            blur1d(a, b, w, h, r, true);
            blur1d(b, a, w, h, r, false);
        }
        img.setRGB(0, 0, w, h, a, 0, w);
    }

    static void blur1d(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        for (int l = 0; l < lines; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = src[index(l, Math.min(Math.max(k, 0), n - 1), w, horizontal)];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                int d = 2 * r + 1;
                dst[index(l, i, w, horizontal)] = 0xFF000000 | ((sr / d) << 16) | ((sg / d) << 8) | (sb / d);
                int out = src[index(l, Math.max(i - r, 0), w, horizontal)];
                int in = src[index(l, Math.min(i + r + 1, n - 1), w, horizontal)];
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** Warm white balance, vignette and sensor noise, like a phone camera frame. */
    static void finish(BufferedImage img, Random rnd) {
        int w = img.getWidth(), h = img.getHeight();
        double cx = w / 2.0, cy = h * 0.4, maxD = Math.hypot(w / 2.0, h * 0.6);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                double v = 1.0 - 0.35 * Math.pow(Math.hypot(x - cx, y - cy) / maxD, 2);
                double n = rnd.nextGaussian() * 3.5;
                int r = clamp(((p >> 16) & 255) * v * 1.03 + n), gg = clamp(((p >> 8) & 255) * v + n), b = clamp((p & 255) * v * 0.95 + n);
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
            }
        }
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
