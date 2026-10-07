package arena;

import engine.Board;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Renders a finished board to PNG with plain java.awt (headless). */
public final class BoardImage {

    private static final Color BACKGROUND = new Color(0xFAFAF7);
    private static final Color EDGE       = new Color(0x2B2B2B);
    private static final Color EMPTY_EDGE = new Color(0xDDDDD8);
    private static final Color DOT        = new Color(0x111111);
    private static final Color TEXT       = new Color(0x222222);
    private static final Color MUTED      = new Color(0x666666);
    /** Fill and label colours for player 0 and player 1. */
    private static final Color[] FILL  = { new Color(0xC9DDF5), new Color(0xF8D7B8) };
    private static final Color[] LABEL = { new Color(0x1F5FA8), new Color(0xB25A12) };

    private BoardImage() {}

    /**
     * @param names   display name of player 0 and player 1
     * @param initials short label written inside each owned box
     * @param caption2 second caption line (context of the game)
     */
    public static void write(Board board, String[] names, String[] initials,
                             String caption2, File out) throws IOException {
        System.setProperty("java.awt.headless", "true");
        int width = 800;
        int margin = 80;
        int cell = (width - 2 * margin) / (board.getCols() - 1);
        int gridH = cell * (board.getRows() - 1);
        int top = 60;
        int height = top + gridH + 150;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, height);

        int boxRows = board.getRows() - 1, boxCols = board.getCols() - 1;
        Font boxFont = new Font(Font.SANS_SERIF, Font.BOLD, cell / 4);

        // Owned boxes.
        for (int r = 0; r < boxRows; r++) {
            for (int c = 0; c < boxCols; c++) {
                int owner = board.getBoxOwner(r, c);
                if (owner < 0) continue;
                int x = margin + c * cell, y = top + r * cell;
                g.setColor(FILL[owner]);
                g.fillRect(x, y, cell, cell);
                g.setColor(LABEL[owner]);
                g.setFont(boxFont);
                drawCentered(g, initials[owner], x + cell / 2, y + cell / 2);
            }
        }

        // Edges.
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int r = 0; r < board.getRows(); r++) {
            for (int c = 0; c < board.getCols() - 1; c++) {
                g.setColor(board.isHEdgeSet(r, c) ? EDGE : EMPTY_EDGE);
                g.drawLine(margin + c * cell, top + r * cell, margin + (c + 1) * cell, top + r * cell);
            }
        }
        for (int r = 0; r < board.getRows() - 1; r++) {
            for (int c = 0; c < board.getCols(); c++) {
                g.setColor(board.isVEdgeSet(r, c) ? EDGE : EMPTY_EDGE);
                g.drawLine(margin + c * cell, top + r * cell, margin + c * cell, top + (r + 1) * cell);
            }
        }

        // Dots.
        g.setColor(DOT);
        int d = 14;
        for (int r = 0; r < board.getRows(); r++)
            for (int c = 0; c < board.getCols(); c++)
                g.fillOval(margin + c * cell - d / 2, top + r * cell - d / 2, d, d);

        // Caption.
        int s0 = board.getScore(0), s1 = board.getScore(1);
        int y = top + gridH + 70;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        String left = names[0] + "  " + s0;
        String mid = "  –  ";
        String right = s1 + "  " + names[1];
        FontMetrics fm = g.getFontMetrics();
        int total = fm.stringWidth(left) + fm.stringWidth(mid) + fm.stringWidth(right);
        int x = (width - total) / 2;
        g.setColor(LABEL[0]);
        g.drawString(left, x, y);
        x += fm.stringWidth(left);
        g.setColor(TEXT);
        g.drawString(mid, x, y);
        x += fm.stringWidth(mid);
        g.setColor(LABEL[1]);
        g.drawString(right, x, y);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        g.setColor(MUTED);
        fm = g.getFontMetrics();
        g.drawString(caption2, (width - fm.stringWidth(caption2)) / 2, y + 40);

        g.dispose();
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "png", out);
    }

    private static void drawCentered(Graphics2D g, String s, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }
}
