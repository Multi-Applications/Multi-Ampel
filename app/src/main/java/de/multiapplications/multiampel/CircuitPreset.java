package de.multiapplications.multiampel;
public enum CircuitPreset {

    SINGLE(1, 1, new int[][]{
            {2},
            {3},
            {1},
            {0}
    }),
    CROSSING(2, 2, new int[][]{
            {2, 0},
            {3, 0},
            {0, 1},
            {0, 2},
            {0, 3}
    }),
    T_JUNCTION(3, 3, new int[][]{
            {2, 0, 0},
            {3, 0, 0},
            {0, 2, 0},
            {0, 3, 0},
            {0, 0, 2},
            {0, 0, 3}
    }),
    FOUR_WAY(4, 4, new int[][]{
            {2, 0, 0, 0},
            {3, 0, 0, 0},
            {0, 2, 0, 0},
            {0, 3, 0, 0},
            {0, 0, 2, 0},
            {0, 0, 3, 0},
            {0, 0, 0, 2},
            {0, 0, 0, 3}
    });

    public static final int RED = 0;
    public static final int RED_YELLOW = 1;
    public static final int GREEN = 2;
    public static final int YELLOW = 3;

    private static final long GREEN_TIME = 8000L;
    private static final long SWITCH_TIME = 2000L;

    public final int minDevices;
    public final int maxDevices;
    private final int[][] phases;

    CircuitPreset(int minDevices, int maxDevices, int[][] phases) {
        this.minDevices = minDevices;
        this.maxDevices = maxDevices;
        this.phases = phases;
    }

    public int phaseCount() {
        return phases.length;
    }

    public int colorFor(int phase, int device) {
        int[] colors = phases[((phase % phases.length) + phases.length) % phases.length];
        int index = ((device % colors.length) + colors.length) % colors.length;
        return colors[index];
    }

    public long[] defaultDurations() {
        long[] durations = new long[phases.length];
        for (int i = 0; i < phases.length; i++) {
            durations[i] = phases[i][0] == GREEN ? GREEN_TIME : SWITCH_TIME;
        }
        return durations;
    }
}