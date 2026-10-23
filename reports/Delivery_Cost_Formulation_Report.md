# Mathematical Formulation & Metrics Specification: Delivery Cost in DTN Routing

This document defines the mathematical models, formulas, and metric derivations implemented in [`src/report/DeliveryCostReport.java`](file:///c:/Users/hp/OneDrive/Desktop/IDEA%20LAB/the-one/src/report/DeliveryCostReport.java) for evaluating opportunistic message delivery efficiency in The ONE Simulator.

---

## 1. Transmission Delivery Cost ($C_{\text{tx}}$)

### Definition
The average number of wireless hop forwardings expended by the entire network per successfully delivered message:

$$C_{\text{tx}} = \frac{M_{\text{relayed}}}{M_{\text{delivered}}}$$

Where:
- $M_{\text{relayed}}$: Total number of message transmission relay events across all node contacts during the simulation.
- $M_{\text{delivered}}$: Total number of unique messages that reached their intended destination hosts.

### Relationship to Overhead Ratio
The standard DTN Overhead Ratio is defined as:
$$\text{Overhead Ratio} = \frac{M_{\text{relayed}} - M_{\text{delivered}}}{M_{\text{delivered}}} = \frac{M_{\text{relayed}}}{M_{\text{delivered}}} - 1$$

Therefore:
$$C_{\text{tx}} = 1 + \text{Overhead Ratio}$$

### Theoretical Bounds
- **Lower Bound**: $C_{\text{tx}} = 1.0$ (Ideal single-hop or Direct Delivery, where no relay waste occurs).
- **Upper Bound**: $C_{\text{tx}} \to \infty$ (Unconstrained flooding protocols under heavy network congestion where packets are relayed repeatedly but dropped before reaching destinations).

---

## 2. Cost-Performance Index ($\text{CPI}$)

### Definition
The delivery yield achieved per unit of transmission expenditure:

$$\text{CPI} = \left(\frac{\text{Delivery Probability}}{C_{\text{tx}}}\right) \times 100$$

Where:
$$\text{Delivery Probability} = \frac{M_{\text{delivered}}}{M_{\text{created}}}$$

### Purpose & Interpretation
- Higher $\text{CPI}$ indicates superior routing efficiency.
- A protocol with high delivery probability achieved at excessive forwarding expense (e.g. Epidemic in dense networks: $23.3\%$ delivery at $C_{\text{tx}} = 360.7 \implies \text{CPI} = 0.065$) will score poorly.
- A protocol achieving high delivery with controlled replication (e.g. AdaptiveHybrid in dense networks: $81.9\%$ delivery at $C_{\text{tx}} = 35.0 \implies \text{CPI} = 2.34$) achieves high multi-dimensional efficiency.

---

## 3. Core Hybrid Protocol Selection Formula ($P = w_V V + w_D D + w_H H + w_T T$)

In [`AdaptiveHybridRouter.java`](file:///c:/Users/hp/OneDrive/Desktop/IDEA%20LAB/the-one/src/routing/AdaptiveHybridRouter.java#L452-L482), **$P$ is the core multi-attribute probability score used to dynamically select the appropriate routing protocol mode / forwarding tier** upon encountering a neighbor node:

$$P = w_V V + w_D D + w_H H + w_T T$$

### Parameter Definitions in Hybrid Routing Selection

| Parameter | Symbol | Code Implementation | Physical Meaning in Routing Decisions |
| :--- | :---: | :--- | :--- |
| **Velocity Consistency** | $V$ | `velocityConsistency(mo.speed, mo.prevSpeed)` | Measures speed stability to ensure encounter duration is sufficient for transfer. |
| **Direction Persistence** | $D$ | `directionPersistence(...)` | Directional alignment confirming carrier moves toward target encounter zone. |
| **Historical Predictability** | $H$ | `getPred(addr)` (PRoPHET $P_{(a,b)}$) | Cumulative encounter frequency history with neighbor/destination. |
| **Temporal Recency** | $T$ | $\exp(-\text{gap} / \tau_{\text{rec}})$ | Elapsed time since last contact gap; ensures encounter freshness. |

### Dynamic Density-Adaptive Weights
The weights adapt dynamically based on local node density $d_f$:
$$w_V = 0.30 - 0.10 d_f, \quad w_D = 0.20 + 0.10 d_f, \quad w_H = 0.20 + 0.10 d_f, \quad w_T = 0.30 - 0.10 d_f$$
Where $\sum w = w_V + w_D + w_H + w_T = 1.0$.

### Protocol Mode / Tier Selection Mechanism
The computed probability $P$ determines the routing strategy:

$$S = \alpha \cdot \text{Utility}_{\text{dest}} + (1 - \alpha) \cdot P$$

$$\text{Selected Protocol Tier} = \begin{cases}
\textbf{SINGLE (Direct / Single-Copy Routing)} & \text{if } S \ge \theta_{\text{high}} \quad (0.70) \\
\textbf{TOP\_K (Spray-and-Wait / PRoPHET Spraying)} & \text{if } \theta_{\text{low}} < S < \theta_{\text{high}} \quad (0.25 - 0.70) \\
\textbf{FLOOD (Epidemic / Opportunistic Flooding)} & \text{if } S \le \theta_{\text{low}} \quad (0.25)
\end{cases}$$

### Impact on Delivery Cost ($C_{\text{tx}}$)
Because $P = w_V V + w_D D + w_H H + w_T T$ reliably identifies when a neighbor is a guaranteed carrier ($S \ge \theta_{\text{high}}$), the router delegates copies conservatively via `SINGLE` or `TOP_K`, which directly drives down transmission delivery cost ($C_{\text{tx}} = 20.94\,\text{tx/msg}$ vs. Epidemic's $85.80\,\text{tx/msg}$).

---

## 4. Bandwidth Delivery Cost ($C_{\text{bw}}$)

### Definition
The byte-level network burden incurred across wireless links per byte successfully delivered:

$$C_{\text{bw}} = \frac{\sum_{r \in \text{Relays}} \text{Size}(m_r)}{\sum_{d \in \text{Delivered}} \text{Size}(m_d)}$$

### Purpose
While $C_{\text{tx}}$ treats all packets equally, $C_{\text{bw}}$ captures the physical payload traffic (in bytes/byte) injected into the medium, which is critical when packet sizes vary between $500\,\text{kB}$ and $1\,\text{MB}$.

---

## 5. Per-Message Delivery Cost ($C_{\text{msg}}$)

For each delivered message $m \in \mathcal{D}$, the individual hop path and transmission expenditures are logged:

$$C_{\text{msg}}(m) = \sum_{u \in \mathcal{V}} \text{Transfers}(m, u)$$

Where $\mathcal{V}$ is the set of all nodes in the network. This enables per-packet trajectory auditing and inspection of routing bottlenecks.

---

## 6. Implementation Reference

The Java class [`DeliveryCostReport.java`](file:///c:/Users/hp/OneDrive/Desktop/IDEA%20LAB/the-one/src/report/DeliveryCostReport.java) implements:
- `MessageListener` (`messageTransferred`, `newMessageCreated`, `messageDeleted`)
- Real-time tracking of message lifetimes, hop counts, and cumulative transmission volumes
- Automatic reporting at simulation termination
