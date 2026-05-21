package rinha;

public final class KDTree {
    private static final int DIMS          = 14;
    private static final int K             = 5;
    // KD-tree fast pass: visits enough to reliably identify clearly-fraud (3-5)
    // and clearly-legit (0) queries without triggering exact fallback.
    private static final int APPROX_VISITS = 1_000;
    private static final int STACK_SIZE    = 1_024; // > APPROX_VISITS + 1

    /**
     * Returns fraud neighbor count among K nearest.
     * Fast path: KD-tree approximate. If result is borderline (1 or 2 fraud
     * neighbors — the only zone where a wrong approximation changes the decision),
     * falls back to an exact linear scan.
     */
    public static int fraudCount(short[] vectors, byte[] labels, int N, int[] query) {
        int approx = approxCount(vectors, labels, N, query);
        if (approx == 1 || approx == 2) {
            return exactCount(vectors, labels, N, query);
        }
        return approx;
    }

    private static int approxCount(short[] vectors, byte[] labels, int N, int[] query) {
        long[] bestDist = new long[K];
        java.util.Arrays.fill(bestDist, Long.MAX_VALUE);
        byte[] bestLbl  = new byte[K];
        long   worst    = Long.MAX_VALUE;

        int[]  sPos   = new int[STACK_SIZE];
        int[]  sDepth = new int[STACK_SIZE];
        long[] sDim2  = new long[STACK_SIZE];
        int    top = 0, visits = 0;

        sPos[0] = 0; sDepth[0] = 0; sDim2[0] = 0L; top = 1;

        while (top > 0 && visits < APPROX_VISITS) {
            top--;
            int  pos   = sPos[top];
            int  depth = sDepth[top];
            long dim2  = sDim2[top];
            if (pos >= N || dim2 >= worst) continue;
            visits++;

            int  base = pos * DIMS;
            long d    = 0L;
            for (int j = 0; j < DIMS; j++) {
                long diff = (long) query[j] - (long) vectors[base + j];
                d += diff * diff;
                if (d >= worst) { d = Long.MAX_VALUE; break; }
            }

            if (d < worst) {
                int mi = 0;
                for (int m = 1; m < K; m++)
                    if (bestDist[m] > bestDist[mi]) mi = m;
                bestDist[mi] = d;
                bestLbl[mi]  = labels[pos];
                worst = 0L;
                for (int m = 0; m < K; m++)
                    if (bestDist[m] > worst) worst = bestDist[m];
            }

            int  dim  = depth % DIMS;
            long qVal = (long) query[dim];
            long nVal = (long) vectors[base + dim];
            long hyp2 = (qVal - nVal) * (qVal - nVal);

            int near, far;
            if (qVal <= nVal) { near = pos * 2 + 1; far = pos * 2 + 2; }
            else              { near = pos * 2 + 2; far = pos * 2 + 1; }

            if (far < N)  { sPos[top] = far;  sDepth[top] = depth + 1; sDim2[top] = hyp2; top++; }
            if (near < N) { sPos[top] = near; sDepth[top] = depth + 1; sDim2[top] = 0L;   top++; }
        }

        int cnt = 0;
        for (int i = 0; i < K; i++)
            if (bestDist[i] < Long.MAX_VALUE && bestLbl[i] == 1) cnt++;
        return cnt;
    }

    private static int exactCount(short[] vectors, byte[] labels, int N, int[] query) {
        long[] bestDist = new long[K];
        java.util.Arrays.fill(bestDist, Long.MAX_VALUE);
        byte[] bestLbl = new byte[K];
        long   worst   = Long.MAX_VALUE;

        outer:
        for (int i = 0; i < N; i++) {
            int  base = i * DIMS;
            long d    = 0L;
            for (int j = 0; j < DIMS; j++) {
                long diff = query[j] - (long) vectors[base + j];
                d += diff * diff;
                if (d >= worst) continue outer;
            }
            int mi = 0;
            for (int m = 1; m < K; m++)
                if (bestDist[m] > bestDist[mi]) mi = m;
            bestDist[mi] = d;
            bestLbl[mi]  = labels[i];
            worst = 0L;
            for (int m = 0; m < K; m++)
                if (bestDist[m] > worst) worst = bestDist[m];
        }

        int cnt = 0;
        for (int i = 0; i < K; i++)
            if (bestDist[i] < Long.MAX_VALUE && bestLbl[i] == 1) cnt++;
        return cnt;
    }
}
