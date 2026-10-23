package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Adaptive hybrid router (version 4).
 *
 * Changes versus version 3:
 *  1. A real, network-wide copy budget travels with each message
 *     (Spray-and-Wait style). The tiers decide how much of the budget is
 *     handed to a neighbor: SINGLE = large share, TOP_K = medium share,
 *     FLOOD = small share. The old per-node "forwardedCopies" counter is gone.
 *  2. The reconnect score is destination-aware: PRoPHET delivery
 *     predictability and FRESH-style contact recency toward the message's
 *     destination are the primary signal; the mobility features are secondary.
 *  3. Neighbors that already hold the message are skipped (no DENIED_OLD loop).
 *  4. Delivery acknowledgements are exchanged on contact; acked messages are
 *     purged from buffers (less buffer churn, fewer useless relays).
 *  5. historicalMatch is replaced by PRoPHET predictability toward the neighbor.
 *  6. Last-copy "focus phase": the final copy is only forwarded to a neighbor
 *     whose destination utility is clearly higher than ours.
 *  7. Adaptive gating: low-tier spraying is suppressed when the buffer is
 *     crowded or the message is close to expiry.
 *  8. A lightweight case-based reasoning (CBR) layer stores
 *     (context, tier, outcome) cases and retrieves the nearest ones to
 *     confirm or override the threshold-based tier.
 *  9. Optional diagnostics (debugStats) printed when the JVM exits.
 */
public class AdaptiveHybridRouter extends ActiveRouter {

    public static final String ROUTER_NS = "AdaptiveHybridRouter";
    public static final String COPIES_PROPERTY = ROUTER_NS + ".copies";

    private enum Tier { SINGLE, TOP_K, FLOOD }

    /* ---------------- settings (all optional, defaults below) ------------ */
    private double highThreshold;      // score >= high  -> SINGLE
    private double lowThreshold;       // score <= low   -> FLOOD
    private int initialCopies;         // L, copy budget per message
    private double shareSingle;        // fraction of copies handed over per tier
    private double shareTopK;
    private double shareFlood;
    private boolean focusPhase;        // forward the last copy to better nodes
    private double focusMargin;        // required utility advantage for focus
    private double alpha;              // weight of destination utility in score
    private double wPred;              // PRoPHET vs FRESH inside dest utility
    private double freshDecay;         // seconds, FRESH recency decay
    private double recencyDecay;       // seconds, neighbor gap recency decay
    private double pInit;              // PRoPHET constants
    private double beta;
    private double gamma;
    private double secondsInTimeUnit;
    private double floodOccupancyLimit;// no FLOOD-tier spraying above this
    private double minTtlFracFlood;    // no FLOOD-tier spraying below this
    private double motionSampleInterval;
    private double maxMotionGap;
    private boolean useCBR;
    private int cbrK;
    private int cbrMinCases;
    private int cbrMaxCases;
    private double cbrEpsilon;
    private double cbrCostWeight;
    private double cbrSwitchMargin;
    private double maintenanceInterval;
    private boolean debugStats;

    /* ---------------- per-node state ------------------------------------- */
    private Map<Integer, Double> preds = new HashMap<Integer, Double>();
    private double lastAgeTime = 0.0;
    private Map<Integer, Double> lastSeen = new HashMap<Integer, Double>();
    private Map<Integer, Double> lastDownTime = new HashMap<Integer, Double>();
    private Map<Integer, Double> lastGap = new HashMap<Integer, Double>();
    private Map<Integer, Motion> motions = new HashMap<Integer, Motion>();
    private Set<String> deliveredIds = new HashSet<String>();
    private Map<String, Set<Integer>> sentTo =
            new HashMap<String, Set<Integer>>();
    /** copies handed to a receiver, key = msgId|receiverAddress */
    private Map<String, Integer> pendingShares = new HashMap<String, Integer>();
    private List<Case> cases = new ArrayList<Case>();
    private double lastMaintenance = 0.0;
    private Random rng = null;

    /* ---------------- diagnostics (static, whole simulation) ------------- */
    private static final int[] TIER_COUNT = new int[3];
    private static final int[] SCORE_HIST = new int[10];
    private static int cbrOverrides = 0;
    private static int focusHandoffs = 0;
    private static int ackPurges = 0;
    private static int relayHandoffs = 0;
    private static boolean hookRegistered = false;

    private static class Motion {
        double x, y, t;
        double speed, prevSpeed;
        double dx, dy, prevDx, prevDy;
    }

    private static class Case {
        double[] f;
        Tier tier;
        double shareFrac;
        String msgId;
        double expiresAt;
        boolean resolved;
        double reward;
    }

    private static final Comparator<Message> RECEIVE_ORDER =
            new Comparator<Message>() {
                public int compare(Message a, Message b) {
                    return Double.compare(a.getReceiveTime(),
                            b.getReceiveTime());
                }
            };

    /* ---------------- construction --------------------------------------- */

    public AdaptiveHybridRouter(Settings s) {
        super(s);
        Settings c = new Settings(ROUTER_NS);

        highThreshold = d(c, "highThreshold", 0.55);
        lowThreshold = d(c, "lowThreshold", 0.25);
        initialCopies = i(c, "initialCopies", 14);
        shareSingle = d(c, "shareSingle", 0.75);
        shareTopK = d(c, "shareTopK", 0.50);
        shareFlood = d(c, "shareFlood", 0.25);
        focusPhase = b(c, "focusPhase", true);
        focusMargin = d(c, "focusMargin", 0.02);
        alpha = d(c, "alpha", 0.70);
        wPred = d(c, "wPred", 0.60);
        freshDecay = d(c, "freshDecay", 900.0);
        recencyDecay = d(c, "recencyDecay", 300.0);
        pInit = d(c, "pInit", 0.75);
        beta = d(c, "beta", 0.35);
        gamma = d(c, "gamma", 0.995);
        secondsInTimeUnit = d(c, "secondsInTimeUnit", 30.0);
        floodOccupancyLimit = d(c, "floodOccupancyLimit", 0.80);
        minTtlFracFlood = d(c, "minTtlFracFlood", 0.10);
        motionSampleInterval = d(c, "motionSampleInterval", 1.0);
        maxMotionGap = d(c, "maxMotionGap", 10.0);
        useCBR = b(c, "useCBR", true);
        cbrK = i(c, "cbrK", 7);
        cbrMinCases = i(c, "cbrMinCases", 30);
        cbrMaxCases = i(c, "cbrMaxCases", 400);
        cbrEpsilon = d(c, "cbrEpsilon", 0.05);
        cbrCostWeight = d(c, "cbrCostWeight", 0.10);
        cbrSwitchMargin = d(c, "cbrSwitchMargin", 0.03);
        maintenanceInterval = d(c, "maintenanceInterval", 30.0);
        debugStats = b(c, "debugStats", false);

        if (debugStats) {
            registerStatsHook();
        }
    }

    protected AdaptiveHybridRouter(AdaptiveHybridRouter r) {
        super(r);
        this.highThreshold = r.highThreshold;
        this.lowThreshold = r.lowThreshold;
        this.initialCopies = r.initialCopies;
        this.shareSingle = r.shareSingle;
        this.shareTopK = r.shareTopK;
        this.shareFlood = r.shareFlood;
        this.focusPhase = r.focusPhase;
        this.focusMargin = r.focusMargin;
        this.alpha = r.alpha;
        this.wPred = r.wPred;
        this.freshDecay = r.freshDecay;
        this.recencyDecay = r.recencyDecay;
        this.pInit = r.pInit;
        this.beta = r.beta;
        this.gamma = r.gamma;
        this.secondsInTimeUnit = r.secondsInTimeUnit;
        this.floodOccupancyLimit = r.floodOccupancyLimit;
        this.minTtlFracFlood = r.minTtlFracFlood;
        this.motionSampleInterval = r.motionSampleInterval;
        this.maxMotionGap = r.maxMotionGap;
        this.useCBR = r.useCBR;
        this.cbrK = r.cbrK;
        this.cbrMinCases = r.cbrMinCases;
        this.cbrMaxCases = r.cbrMaxCases;
        this.cbrEpsilon = r.cbrEpsilon;
        this.cbrCostWeight = r.cbrCostWeight;
        this.cbrSwitchMargin = r.cbrSwitchMargin;
        this.maintenanceInterval = r.maintenanceInterval;
        this.debugStats = r.debugStats;
        /* per-node state starts fresh via the field initializers */
    }

    @Override
    public AdaptiveHybridRouter replicate() {
        return new AdaptiveHybridRouter(this);
    }

    private static double d(Settings s, String k, double def) {
        return s.contains(k) ? s.getDouble(k) : def;
    }

    private static int i(Settings s, String k, int def) {
        return s.contains(k) ? s.getInt(k) : def;
    }

    private static boolean b(Settings s, String k, boolean def) {
        return s.contains(k) ? s.getBoolean(k) : def;
    }

    /* ---------------- message life cycle ---------------------------------- */

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        msg.addProperty(COPIES_PROPERTY, Integer.valueOf(initialCopies));
        addToMessages(msg, true);
        return true;
    }

    private int getCopies(Message m) {
        Object v = m.getProperty(COPIES_PROPERTY);
        return (v instanceof Integer) ? ((Integer) v).intValue() : 1;
    }

    private static String shareKey(String msgId, int receiverAddr) {
        return msgId + "|" + receiverAddr;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);

        if (msg.getTo() == getHost()) {
            deliveredIds.add(id);
            return msg;
        }

        int share = 1;
        if (from.getRouter() instanceof AdaptiveHybridRouter) {
            AdaptiveHybridRouter fr = (AdaptiveHybridRouter) from.getRouter();
            Integer v = fr.pendingShares.get(
                    shareKey(id, getHost().getAddress()));
            if (v != null) {
                share = v.intValue();
            }
        }
        if (msg.getProperty(COPIES_PROPERTY) != null) {
            msg.updateProperty(COPIES_PROPERTY, Integer.valueOf(share));
        }
        return msg;
    }

    /**
     * Called on the sender right before the transfer is finalized. Deduct the
     * handed-over copies; drop our copy if the budget is exhausted (focus
     * hand-off or delivery to the destination).
     */
    @Override
    protected void transferDone(Connection con) {
        super.transferDone(con);

        Message sent = con.getMessage();
        if (sent == null) {
            return;
        }
        String id = sent.getId();
        DTNHost other = con.getOtherNode(getHost());

        if (sent.getTo() == other) {
            deliveredIds.add(id);
            if (hasMessage(id)) {
                deleteMessage(id, false);
            }
            return;
        }

        Integer share = pendingShares.get(shareKey(id, other.getAddress()));
        int s = (share == null) ? 1 : share.intValue();

        Set<Integer> set = sentTo.get(id);
        if (set == null) {
            set = new HashSet<Integer>();
            sentTo.put(id, set);
        }
        set.add(other.getAddress());

        Message mine = getMessage(id);
        if (mine != null) {
            int left = getCopies(mine) - s;
            if (left <= 0) {
                double occ = getOccupancy();
                double myUtil = destUtility(this, mine.getTo().getAddress(), SimClock.getTime());
                if (occ > 0.90 && myUtil < 0.15) {
                    deleteMessage(id, false);
                } else {
                    mine.updateProperty(COPIES_PROPERTY, Integer.valueOf(1));
                }
            } else {
                mine.updateProperty(COPIES_PROPERTY, Integer.valueOf(left));
            }
        }
    }

    /* ---------------- contacts -------------------------------------------- */

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        DTNHost other = con.getOtherNode(getHost());
        if (other == null) {
            return;
        }
        int addr = other.getAddress();
        double now = SimClock.getTime();

        if (con.isUp()) {
            Double down = lastDownTime.get(addr);
            if (down != null) {
                lastGap.put(addr, Double.valueOf(now - down.doubleValue()));
            }
            lastSeen.put(addr, Double.valueOf(now));

            if (other.getRouter() instanceof AdaptiveHybridRouter) {
                AdaptiveHybridRouter o = (AdaptiveHybridRouter) other.getRouter();
                agePreds(now);
                updateDirectPred(addr);
                updateTransitivePreds(addr, o);
                deliveredIds.addAll(o.deliveredIds);
                o.deliveredIds.addAll(deliveredIds);
                purgeAcked();
                o.purgeAcked();
            }
        } else {
            lastDownTime.put(addr, Double.valueOf(now));
            lastSeen.put(addr, Double.valueOf(now));
        }
    }

    /* ---------------- PRoPHET predictability ------------------------------ */

    private double getPred(int addr) {
        Double v = preds.get(addr);
        return v == null ? 0.0 : v.doubleValue();
    }

    private void updateDirectPred(int addr) {
        double old = getPred(addr);
        preds.put(addr, Double.valueOf(old + (1.0 - old) * pInit));
    }

    private void updateTransitivePreds(int viaAddr, AdaptiveHybridRouter o) {
        double pab = getPred(viaAddr);
        int me = getHost().getAddress();
        for (Map.Entry<Integer, Double> e : o.preds.entrySet()) {
            int c = e.getKey().intValue();
            if (c == me || c == viaAddr) {
                continue;
            }
            double pac = getPred(c);
            double pbc = e.getValue().doubleValue();
            preds.put(c, Double.valueOf(pac + (1.0 - pac) * pab * pbc * beta));
        }
    }

    private void agePreds(double now) {
        double units = (now - lastAgeTime) / secondsInTimeUnit;
        if (units < 1.0) {
            return;
        }
        double mult = Math.pow(gamma, units);
        for (Map.Entry<Integer, Double> e : preds.entrySet()) {
            e.setValue(Double.valueOf(e.getValue().doubleValue() * mult));
        }
        lastAgeTime = now;
    }

    /* ---------------- utility and score ----------------------------------- */

    private double clamp(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    /** FRESH-style recency of the last contact with node addr. */
    private double freshness(int addr, double now) {
        Double t = lastSeen.get(addr);
        if (t == null) {
            return 0.0;
        }
        return Math.exp(-Math.max(0.0, now - t.doubleValue()) / freshDecay);
    }

    /** How promising router r is as a carrier toward destination dest. */
    private double destUtility(AdaptiveHybridRouter r, int dest, double now) {
        double p = r.getPred(dest);
        double f = r.freshness(dest, now);
        return clamp(wPred * p + (1.0 - wPred) * f);
    }

    private double densityFactor(int neighborCount) {
        double low = 3.0;
        double high = 10.0;
        if (neighborCount <= low) {
            return 0.0;
        }
        if (neighborCount >= high) {
            return 1.0;
        }
        return (neighborCount - low) / (high - low);
    }

    private double velocityConsistency(double cur, double prev) {
        if (cur <= 0.0 || prev <= 0.0) {
            return 0.0;
        }
        double avg = (cur + prev) / 2.0;
        return clamp(1.0 - Math.abs(cur - prev) / avg);
    }

    private double directionPersistence(double pdx, double pdy,
            double cdx, double cdy) {
        double pm = Math.sqrt(pdx * pdx + pdy * pdy);
        double cm = Math.sqrt(cdx * cdx + cdy * cdy);
        if (pm == 0.0 || cm == 0.0) {
            return 0.0;
        }
        return clamp((pdx * cdx + pdy * cdy) / (pm * cm));
    }

    /** Secondary signal: how regular/predictable this neighbor looks. */
    private double mobilityScore(int addr, int neighborCount) {
        Motion mo = motions.get(addr);
        double vc = 0.0;
        double dp = 0.0;
        if (mo != null) {
            vc = velocityConsistency(mo.speed, mo.prevSpeed);
            dp = directionPersistence(mo.prevDx, mo.prevDy, mo.dx, mo.dy);
        }
        double hist = clamp(getPred(addr));
        Double gap = lastGap.get(addr);
        double rec = (gap == null) ? 0.0
                : clamp(Math.exp(-gap.doubleValue() / recencyDecay));

        double df = densityFactor(neighborCount);
        double w1 = 0.30 + df * (0.20 - 0.30);
        double w2 = 0.20 + df * (0.30 - 0.20);
        double w3 = 0.20 + df * (0.30 - 0.20);
        double w4 = 0.30 + df * (0.20 - 0.30);
        return clamp(w1 * vc + w2 * dp + w3 * hist + w4 * rec);
    }

    private Tier ruleTier(double score) {
        if (score >= highThreshold) {
            return Tier.SINGLE;
        }
        if (score <= lowThreshold) {
            return Tier.FLOOD;
        }
        return Tier.TOP_K;
    }

    private double fraction(Tier t) {
        switch (t) {
            case SINGLE:
                return shareSingle;
            case TOP_K:
                return shareTopK;
            default:
                return shareFlood;
        }
    }

    /* ---------------- movement observation -------------------------------- */

    private void observeNeighbors(double now) {
        List<Connection> cons = getConnections();
        if (cons == null) {
            return;
        }
        DTNHost me = getHost();
        for (Connection con : cons) {
            if (con == null || !con.isUp()) {
                continue;
            }
            DTNHost o = con.getOtherNode(me);
            if (o != null) {
                observe(o, now);
            }
        }
    }

    private void observe(DTNHost h, double now) {
        int a = h.getAddress();
        lastSeen.put(a, Double.valueOf(now));

        double x = h.getLocation().getX();
        double y = h.getLocation().getY();
        Motion mo = motions.get(a);

        if (mo == null) {
            mo = new Motion();
            mo.x = x;
            mo.y = y;
            mo.t = now;
            motions.put(a, mo);
            return;
        }

        double dt = now - mo.t;
        if (dt < motionSampleInterval) {
            return;
        }
        if (dt > maxMotionGap) {
            /* observation gap (we were disconnected): restart the history */
            mo.x = x;
            mo.y = y;
            mo.t = now;
            mo.speed = mo.prevSpeed = 0.0;
            mo.dx = mo.dy = mo.prevDx = mo.prevDy = 0.0;
            return;
        }

        double dx = x - mo.x;
        double dy = y - mo.y;
        mo.prevSpeed = mo.speed;
        mo.prevDx = mo.dx;
        mo.prevDy = mo.dy;
        mo.speed = Math.sqrt(dx * dx + dy * dy) / dt;
        mo.dx = dx;
        mo.dy = dy;
        mo.x = x;
        mo.y = y;
        mo.t = now;
    }

    /* ---------------- update loop ----------------------------------------- */

    @Override
    public void update() {
        super.update();

        double now = SimClock.getTime();
        observeNeighbors(now);

        if (now - lastMaintenance >= maintenanceInterval) {
            maintain(now);
            lastMaintenance = now;
        }

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        /* 1. direct delivery always has priority */
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        /* 2. budget-aware relaying */
        forwardAdaptively(now);
    }

    private void forwardAdaptively(double now) {
        List<Message> msgs = new ArrayList<Message>(getMessageCollection());
        if (msgs.isEmpty()) {
            return;
        }

        double occupancy = getOccupancy();
        List<Connection> cons = new ArrayList<Connection>(getConnections());
        int density = cons.size();

        for (Connection con : cons) {
            if (!con.isReadyForTransfer()) {
                continue;
            }
            DTNHost other = con.getOtherNode(getHost());
            if (other == null
                    || !(other.getRouter() instanceof AdaptiveHybridRouter)) {
                continue;
            }
            final AdaptiveHybridRouter o = (AdaptiveHybridRouter) other.getRouter();
            final double simNow = now;

            List<Message> sortedMsgs = new ArrayList<Message>(msgs);
            Collections.sort(sortedMsgs, new Comparator<Message>() {
                public int compare(Message m1, Message m2) {
                    double u1 = destUtility(o, m1.getTo().getAddress(), simNow)
                              - destUtility(AdaptiveHybridRouter.this, m1.getTo().getAddress(), simNow);
                    double u2 = destUtility(o, m2.getTo().getAddress(), simNow)
                              - destUtility(AdaptiveHybridRouter.this, m2.getTo().getAddress(), simNow);
                    return Double.compare(u2, u1);
                }
            });

            for (Message m : sortedMsgs) {
                if (tryRelay(m, con, other, o, occupancy, density, now)) {
                    return; /* one transfer per update call */
                }
            }
        }
    }

    private boolean tryRelay(Message m, Connection con, DTNHost other,
            AdaptiveHybridRouter o, double occupancy, int density,
            double now) {

        String id = m.getId();
        int otherAddr = other.getAddress();

        if (m.getTo() == other || isSending(id)) {
            return false;
        }
        if (o.hasMessage(id) || o.deliveredIds.contains(id)
                || deliveredIds.contains(id)) {
            return false;
        }
        Set<Integer> already = sentTo.get(id);
        if (already != null && already.contains(otherAddr)) {
            return false;
        }

        int copies = getCopies(m);
        int dest = m.getTo().getAddress();
        double ttlFrac = ttlFraction(m);

        double destMine = destUtility(this, dest, now);
        double destTheirs = destUtility(o, dest, now);
        double advantage = destTheirs - destMine;

        double mob = mobilityScore(otherAddr, density);
        double score = clamp(alpha * destTheirs + (1.0 - alpha) * mob);
        Tier ruleTier = ruleTier(score);

        int share;
        Tier tier = ruleTier;
        boolean focus = false;

        if (copies <= 1) {
            /* wait/focus phase: only hand copy if other has advantage */
            if (!focusPhase || advantage < focusMargin) {
                return false;
            }
            share = 1;
            focus = true;
        } else {
            double[] f = features(score, advantage, density, occupancy,
                    copies, ttlFrac);
            tier = selectTier(f, ruleTier);

            if (tier == Tier.FLOOD && (occupancy > floodOccupancyLimit
                    || ttlFrac < minTtlFracFlood)) {
                return false;
            }

            double frac = fraction(tier);
            /* do not give the majority of the budget to a worse carrier */
            if (advantage < 0.0 && tier != Tier.FLOOD) {
                frac = shareFlood;
            }
            share = (int) Math.round(copies * frac);
            share = Math.max(1, Math.min(copies - 1, share));

            if (useCBR) {
                recordCase(f, tier, (double) share / copies, id, m);
            }
        }

        String key = shareKey(id, otherAddr);
        pendingShares.put(key, Integer.valueOf(share));

        int r = startTransfer(m, con);
        if (r == RCV_OK) {
            TIER_COUNT[tier.ordinal()]++;
            SCORE_HIST[Math.min(9, (int) (score * 10))]++;
            if (focus) {
                focusHandoffs++;
            } else {
                relayHandoffs++;
            }
            return true;
        }
        pendingShares.remove(key);
        if (!focus && useCBR && !cases.isEmpty()) {
            /* the transfer did not start: discard the case we just added */
            cases.remove(cases.size() - 1);
        }
        return false;
    }

    private double ttlFraction(Message m) {
        if (msgTtl == Integer.MAX_VALUE || msgTtl <= 0) {
            return 1.0;
        }
        return clamp(m.getTtl() / (double) msgTtl);
    }

    private double getOccupancy() {
        long bs = getBufferSize();
        if (bs == Integer.MAX_VALUE || bs <= 0) {
            return 0.0;
        }
        return clamp(1.0 - getFreeBufferSize() / (double) bs);
    }

    /* ---------------- case-based reasoning -------------------------------- */

    private double[] features(double score, double advantage, int density,
            double occupancy, int copies, double ttlFrac) {
        return new double[] {
                score,
                clamp((advantage + 1.0) / 2.0),
                densityFactor(density),
                occupancy,
                clamp(copies / (double) Math.max(1, initialCopies)),
                ttlFrac };
    }

    private void recordCase(double[] f, Tier tier, double shareFrac,
            String msgId, Message m) {
        Case c = new Case();
        c.f = f;
        c.tier = tier;
        c.shareFrac = shareFrac;
        c.msgId = msgId;
        double ttlSec = (msgTtl == Integer.MAX_VALUE) ? 86400.0
                : Math.min(86400.0, msgTtl * 60.0);
        c.expiresAt = SimClock.getTime() + ttlSec;
        cases.add(c);
        if (cases.size() > cbrMaxCases) {
            cases.remove(0);
        }
    }

    private Random random() {
        if (rng == null) {
            rng = new Random(7919L * getHost().getAddress() + 13L);
        }
        return rng;
    }

    /**
     * Retrieve the K nearest resolved cases and compare the average reward of
     * each tier. Switch away from the threshold tier only when another tier
     * is clearly better in similar contexts. Epsilon-greedy exploration keeps
     * all tiers represented in the case base.
     */
    private Tier selectTier(double[] f, Tier ruleTier) {
        if (!useCBR) {
            return ruleTier;
        }
        if (random().nextDouble() < cbrEpsilon) {
            return Tier.values()[random().nextInt(Tier.values().length)];
        }

        List<Case> resolved = new ArrayList<Case>();
        for (Case c : cases) {
            if (c.resolved) {
                resolved.add(c);
            }
        }
        if (resolved.size() < cbrMinCases) {
            return ruleTier;
        }

        final Map<Case, Double> dist = new HashMap<Case, Double>();
        for (Case c : resolved) {
            double s = 0.0;
            for (int k = 0; k < f.length; k++) {
                double diff = f[k] - c.f[k];
                s += diff * diff;
            }
            dist.put(c, Double.valueOf(Math.sqrt(s)));
        }
        Collections.sort(resolved, new Comparator<Case>() {
            public int compare(Case a, Case b) {
                return Double.compare(dist.get(a).doubleValue(),
                        dist.get(b).doubleValue());
            }
        });

        double[] sumR = new double[3];
        double[] sumW = new double[3];
        int n = Math.min(cbrK, resolved.size());
        for (int k = 0; k < n; k++) {
            Case c = resolved.get(k);
            double w = 1.0 / (1.0 + dist.get(c).doubleValue());
            sumR[c.tier.ordinal()] += w * c.reward;
            sumW[c.tier.ordinal()] += w;
        }

        Tier best = ruleTier;
        double bestAvg = (sumW[ruleTier.ordinal()] > 0.0)
                ? sumR[ruleTier.ordinal()] / sumW[ruleTier.ordinal()]
                : 0.0;
        for (Tier t : Tier.values()) {
            if (sumW[t.ordinal()] <= 0.0 || t == ruleTier) {
                continue;
            }
            double avg = sumR[t.ordinal()] / sumW[t.ordinal()];
            if (avg > bestAvg + cbrSwitchMargin) {
                best = t;
                bestAvg = avg;
            }
        }
        if (best != ruleTier) {
            cbrOverrides++;
        }
        return best;
    }

    private void resolveCases(double now) {
        for (Case c : cases) {
            if (c.resolved) {
                continue;
            }
            boolean delivered = deliveredIds.contains(c.msgId);
            if (delivered || now >= c.expiresAt) {
                c.reward = (delivered ? 1.0 : 0.0)
                        - cbrCostWeight * c.shareFrac;
                c.resolved = true;
            }
        }
    }

    /* ---------------- maintenance ----------------------------------------- */

    private void maintain(double now) {
        agePreds(now);
        purgeAcked();
        resolveCases(now);

        Iterator<String> it = sentTo.keySet().iterator();
        while (it.hasNext()) {
            if (!hasMessage(it.next())) {
                it.remove();
            }
        }
        Iterator<String> it2 = pendingShares.keySet().iterator();
        while (it2.hasNext()) {
            String key = it2.next();
            String id = key.substring(0, key.lastIndexOf('|'));
            if (!hasMessage(id)) {
                it2.remove();
            }
        }
    }

    /** Remove buffered messages that are known to be delivered already. */
    private void purgeAcked() {
        List<String> remove = new ArrayList<String>();
        for (Message m : getMessageCollection()) {
            if (deliveredIds.contains(m.getId()) && !isSending(m.getId())) {
                remove.add(m.getId());
            }
        }
        for (String id : remove) {
            deleteMessage(id, false);
            ackPurges++;
        }
    }

    /* ---------------- diagnostics ----------------------------------------- */

    private static synchronized void registerStatsHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("=== AdaptiveHybridRouter diagnostics ===");
                System.out.println("relay handoffs  : " + relayHandoffs);
                System.out.println("focus handoffs  : " + focusHandoffs);
                System.out.println("SINGLE/TOP_K/FLOOD : " + TIER_COUNT[0]
                        + " / " + TIER_COUNT[1] + " / " + TIER_COUNT[2]);
                System.out.println("CBR tier overrides  : " + cbrOverrides);
                System.out.println("ack purges          : " + ackPurges);
                StringBuilder sb = new StringBuilder("score histogram (0.0-1.0, 10 bins): ");
                for (int k = 0; k < SCORE_HIST.length; k++) {
                    sb.append(SCORE_HIST[k]).append(' ');
                }
                System.out.println(sb);
            }
        });
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = getMessageCollection();
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        double now = SimClock.getTime();
        Message bestToRemove = null;
        double lowestKeepScore = Double.MAX_VALUE;

        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) {
                continue;
            }

            if (deliveredIds.contains(m.getId())) {
                return m;
            }

            double utility = destUtility(this, m.getTo().getAddress(), now);
            int hops = m.getHopCount();
            double ttlFrac = ttlFraction(m);
            int copies = getCopies(m);

            double keepScore = utility * 3.0 + ttlFrac * 2.0;
            if (hops <= 1) {
                keepScore += 4.0;
            } else if (hops <= 3) {
                keepScore += 1.5;
            }
            if (copies <= 1) {
                keepScore += 1.0;
            }

            if (keepScore < lowestKeepScore) {
                lowestKeepScore = keepScore;
                bestToRemove = m;
            }
        }

        return (bestToRemove != null) ? bestToRemove : super.getNextMessageToRemove(excludeMsgBeingSent);
    }
}

