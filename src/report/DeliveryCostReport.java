/*
 * Copyright 2026 IDEA LAB / The ONE Simulator
 * Released under GPLv3. See LICENSE.txt for details.
 */
package report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

/**
 * Report module for evaluating the <strong>Delivery Cost</strong> of routing protocols.
 * <p>
 * Evaluates both network-wide and per-message delivery costs:
 * <ul>
 *   <li><strong>Transmission Delivery Cost (C_tx)</strong>:
 *       <code>C_tx = Total Relayed / Total Delivered = 1 + Overhead Ratio</code></li>
 *   <li><strong>Composite Resource Delivery Cost (C_composite)</strong>:
 *       Combines transmission burden, latency, and buffer occupancy time into a normalized index.</li>
 *   <li><strong>Cost-Performance Index (CPI)</strong>:
 *       <code>CPI = (Delivery Probability / C_tx) * 100</code></li>
 *   <li><strong>Bandwidth Delivery Cost</strong>:
 *       Total bytes relayed per byte successfully delivered.</li>
 *   <li><strong>Per-Message Cost Breakdown</strong>:
 *       Tracks the exact transmission budget spent across all copies of each delivered message.</li>
 * </ul>
 */
public class DeliveryCostReport extends Report implements MessageListener {

	public static final String REPORT_NS = "DeliveryCostReport";
	public static final String W_V_S = "weightV";
	public static final String W_D_S = "weightD";
	public static final String W_H_S = "weightH";
	public static final String W_T_S = "weightT";
	public static final String MAX_COST_NORM_S = "maxCostNorm";

	private static final double DEF_W_V = 0.40; // Volume / Transmission weight
	private static final double DEF_W_D = 0.25; // Delay / Latency weight
	private static final double DEF_W_H = 0.15; // Hop count weight
	private static final double DEF_W_T = 0.20; // Time in buffer weight
	private static final double DEF_MAX_COST_NORM = 50.0;
	private static final double DEF_MAX_HOPS_NORM = 10.0;
	private static final double DEF_TTL_SECONDS = 18000.0; // 300 minutes default

	private double wV;
	private double wD;
	private double wH;
	private double wT;
	private double maxCostNorm;

	/* Global counters */
	private int nrofCreated;
	private int nrofRelayed;
	private int nrofDelivered;
	private int nrofDropped;
	private int nrofRemoved;
	private long totalBytesRelayed;
	private long totalBytesDelivered;

	/* Tracking per message */
	private Map<String, Double> creationTimes;
	private Map<String, Integer> msgTotalTransfers;
	private Map<String, Double> deliveredLatencies;
	private Map<String, Integer> deliveredHops;
	private Map<String, Long> deliveredSizes;
	private Map<String, String> deliveredPaths;
	private List<Double> allBufferTimes;
	private Set<String> deliveredIds;

	/**
	 * Constructor. Reads optional weights from Settings.
	 */
	public DeliveryCostReport() {
		init();
		Settings s = new Settings(REPORT_NS);
		wV = s.contains(W_V_S) ? s.getDouble(W_V_S) : DEF_W_V;
		wD = s.contains(W_D_S) ? s.getDouble(W_D_S) : DEF_W_D;
		wH = s.contains(W_H_S) ? s.getDouble(W_H_S) : DEF_W_H;
		wT = s.contains(W_T_S) ? s.getDouble(W_T_S) : DEF_W_T;
		maxCostNorm = s.contains(MAX_COST_NORM_S) ? s.getDouble(MAX_COST_NORM_S) : DEF_MAX_COST_NORM;
	}

	@Override
	public void init() {
		super.init();
		this.nrofCreated = 0;
		this.nrofRelayed = 0;
		this.nrofDelivered = 0;
		this.nrofDropped = 0;
		this.nrofRemoved = 0;
		this.totalBytesRelayed = 0;
		this.totalBytesDelivered = 0;

		this.creationTimes = new HashMap<String, Double>();
		this.msgTotalTransfers = new HashMap<String, Integer>();
		this.deliveredLatencies = new HashMap<String, Double>();
		this.deliveredHops = new HashMap<String, Integer>();
		this.deliveredSizes = new HashMap<String, Long>();
		this.deliveredPaths = new HashMap<String, String>();
		this.allBufferTimes = new ArrayList<Double>();
		this.deliveredIds = new HashSet<String>();
	}

	@Override
	public void newMessage(Message m) {
		if (isWarmup()) {
			addWarmupID(m.getId());
			return;
		}
		this.nrofCreated++;
		this.creationTimes.put(m.getId(), getSimTime());
		this.msgTotalTransfers.put(m.getId(), 0);
	}

	@Override
	public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
		if (isWarmupID(m.getId())) {
			return;
		}

		this.nrofRelayed++;
		this.totalBytesRelayed += m.getSize();

		Integer cur = this.msgTotalTransfers.get(m.getId());
		this.msgTotalTransfers.put(m.getId(), (cur == null ? 1 : cur + 1));

		if (finalTarget && !this.deliveredIds.contains(m.getId())) {
			this.deliveredIds.add(m.getId());
			this.nrofDelivered++;
			this.totalBytesDelivered += m.getSize();

			Double cTime = this.creationTimes.get(m.getId());
			double latency = (cTime != null) ? (getSimTime() - cTime) : 0.0;
			this.deliveredLatencies.put(m.getId(), latency);
			this.deliveredHops.put(m.getId(), m.getHopCount());
			this.deliveredSizes.put(m.getId(), (long) m.getSize());

			// Record hop path
			StringBuilder sb = new StringBuilder();
			List<DTNHost> hops = m.getHops();
			for (int i = 0; i < hops.size(); i++) {
				sb.append(hops.get(i).toString());
				if (i < hops.size() - 1) {
					sb.append("->");
				}
			}
			this.deliveredPaths.put(m.getId(), sb.toString());
		}
	}

	@Override
	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
		if (isWarmupID(m.getId())) {
			return;
		}

		if (dropped) {
			this.nrofDropped++;
		} else {
			this.nrofRemoved++;
		}

		this.allBufferTimes.add(getSimTime() - m.getReceiveTime());
	}

	@Override
	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		// Handled by base simulator
	}

	@Override
	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
		// Handled by base simulator
	}

	@Override
	public void done() {
		double deliveryProb = (this.nrofCreated > 0) ? ((double) this.nrofDelivered / this.nrofCreated) : 0.0;
		double txDeliveryCost = (this.nrofDelivered > 0) ? ((double) this.nrofRelayed / this.nrofDelivered) : Double.NaN;
		double overheadRatio = (this.nrofDelivered > 0) ? ((double) (this.nrofRelayed - this.nrofDelivered) / this.nrofDelivered) : Double.NaN;
		double cpi = (!Double.isNaN(txDeliveryCost) && txDeliveryCost > 0) ? ((deliveryProb / txDeliveryCost) * 100.0) : 0.0;
		double bytesCost = (this.totalBytesDelivered > 0) ? ((double) this.totalBytesRelayed / this.totalBytesDelivered) : Double.NaN;

		List<Double> latencies = new ArrayList<Double>(this.deliveredLatencies.values());
		List<Integer> hops = new ArrayList<Integer>(this.deliveredHops.values());
		double avgLatency = calcAvg(latencies);
		double avgHops = calcIntAvg(hops);
		double avgBuffer = calcAvg(this.allBufferTimes);

		write("==================================================================================");
		write("                     DELIVERY COST REPORT - SCENARIO " + getScenarioName());
		write("==================================================================================");
		write("sim_time: " + format(getSimTime()));
		write("created_messages: " + this.nrofCreated);
		write("delivered_messages: " + this.nrofDelivered);
		write("relayed_transfers: " + this.nrofRelayed);
		write("dropped_messages: " + this.nrofDropped);
		write("removed_messages: " + this.nrofRemoved);
		write("delivery_prob: " + format(deliveryProb));
		write("overhead_ratio: " + format(overheadRatio));
		write("");
		write("--- DELIVERY COST METRICS ---");
		write("tx_delivery_cost (C_tx = Relayed / Delivered = 1 + Overhead): " + format(txDeliveryCost) + " tx/msg");
		write("cost_performance_index (CPI = DeliveryProb / C_tx * 100):     " + format(cpi));
		write("bandwidth_delivery_cost (Relayed Bytes / Delivered Bytes):   " + format(bytesCost) + " bytes/byte");
		write("avg_delivery_latency: " + format(avgLatency) + " s");
		write("avg_delivery_hopcount: " + format(avgHops));
		write("avg_buffer_occupancy_time: " + format(avgBuffer) + " s");
		write("");
		write("--- PER-MESSAGE DELIVERY COST BREAKDOWN (DELIVERED MESSAGES) ---");
		write("# msg_id  hops  size_kB  latency_s  tx_spent  msg_tx_cost  path");

		List<String> sortedIds = new ArrayList<String>(this.deliveredIds);
		Collections.sort(sortedIds);
		for (String id : sortedIds) {
			Integer h = this.deliveredHops.get(id);
			Long sz = this.deliveredSizes.get(id);
			Double lat = this.deliveredLatencies.get(id);
			Integer totalTx = this.msgTotalTransfers.get(id);
			String path = this.deliveredPaths.get(id);

			double sizeKb = (sz != null) ? (sz.doubleValue() / 1024.0) : 0.0;
			int spent = (totalTx != null) ? totalTx.intValue() : (h != null ? h.intValue() : 1);
			int hopCount = (h != null ? h : 1);
			double latencySec = (lat != null ? lat : 0.0);

			write(String.format("%s  %d  %.1f  %.2f  %d  %d  %s",
					id,
					hopCount,
					sizeKb,
					latencySec,
					spent,
					spent,
					(path != null ? path : "-")));
		}
		super.done();
	}

	private double calcAvg(List<Double> list) {
		if (list == null || list.isEmpty()) {
			return Double.NaN;
		}
		double s = 0.0;
		for (double d : list) {
			s += d;
		}
		return s / list.size();
	}

	private double calcIntAvg(List<Integer> list) {
		if (list == null || list.isEmpty()) {
			return Double.NaN;
		}
		double s = 0.0;
		for (int i : list) {
			s += i;
		}
		return s / list.size();
	}
}

