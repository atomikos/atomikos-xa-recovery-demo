package demo;

final class Narration {
    private Narration() {}

    static String banner(String text) {
        String bar = "=".repeat(Math.max(text.length() + 4, 70));
        return bar + System.lineSeparator()
                + "  " + text + System.lineSeparator()
                + bar;
    }
}
