package rinha;

public final class KDTree {
    private static final int  DIMS       = 14;
    private static final int  K          = 5;
    // 14D KD-tree visits O(N^(13/14)) nodes exactly — nearly brute force.
    // Capping visits trades a tiny accuracy loss for orders-of-magnitude speedup.
    // 14D KD-tree visits O(N^(13/14)) nodes exactly — nearly brute force.
    // Capping visits trades a tiny accuracy loss for orders-of-magnitude speedup.
    // Max stack depth = 1 + MAX_VISITS (each visit pops 1 and pushes ≤2).
    private static final int  MAX_VISITS = 10_000;
    private static final int  STACK_SIZE = 16_384; // > MAX_VISITS + 1

    /**
     * Returns the fraud count among the K nearest neighbours.
     * vectors[] is laid out in BFS/heap order: position 0 = root,
     * left child of pos p at 2p+1, right child at 2p+2.
     */
    public static int fraudCount(short[] vectors, byte[] labels, int N, int[] query) {
        long[] bestDist = new long[K];
        java.util.Arrays.fill(bestDist, Long.MAX_VALUE);
        byte[] bestLbl  = new byte[K];
        long   worst    = Long.MAX_VALUE;

        int[]  sPos   = new int[STACK_SIZE];
        int[]  sDepth = new int[STACK_SIZE];
        long[] sDim2  = new long[STACK_SIZE];
        int    top    = 0;
        int    visits = 0;

        sPos[0]   = 0;
        sDepth[0] = 0;
        sDim2[0]  = 0L;
        top = 1;

        while (top > 0 && visits < MAX_VISITS) {
            top--;
            int  pos   = sPos[top];
            int  depth = sDepth[top];
            long dim2  = sDim2[top];

            if (pos >= N || dim2 >= worst) continue;
            visits++;

            // Distance to this node (early-exit if already >= worst)
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

            int  dim     = depth % DIMS;
            long qVal    = (long) query[dim];
            long nVal    = (long) vectors[base + dim];
            long hyp2    = (qVal - nVal) * (qVal - nVal);

            int near, far;
            if (qVal <= nVal) { near = pos * 2 + 1; far = pos * 2 + 2; }
            else              { near = pos * 2 + 2; far = pos * 2 + 1; }

            // Push far first (explored second); near last (explored first)
            if (far < N) {
                sPos[top]   = far;
                sDepth[top] = depth + 1;
                sDim2[top]  = hyp2;
                top++;
            }
            if (near < N) {
                sPos[top]   = near;
                sDepth[top] = depth + 1;
                sDim2[top]  = 0L;
                top++;
            }
        }

        int cnt = 0;
        for (int i = 0; i < K; i++)
            if (bestDist[i] < Long.MAX_VALUE && bestLbl[i] == 1) cnt++;
        return cnt;
    }
}
