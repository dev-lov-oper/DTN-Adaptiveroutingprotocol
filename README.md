# Adaptive Hybrid Routing Protocol for Delay-Tolerant Networks (DTN)

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](compile.bat)
[![Platform](https://img.shields.io/badge/platform-The%20ONE%20Simulator-blue.svg)](http://akeranen.github.io/the-one/)
[![Language](https://img.shields.io/badge/language-Java%208%2B-orange.svg)](src/routing/AdaptiveHybridRouter.java)
[![License](https://img.shields.io/badge/license-GPL--3.0-green.svg)](LICENSE.txt)

An advanced, multi-tier, density-adaptive routing protocol designed for opportunistic and Delay-Tolerant Networks (DTNs), implemented and benchmarked within **The ONE (Opportunistic Network Environment) Simulator**.

---

## Table of Contents
- [1. Overview & Motivation](#1-overview--motivation)
- [2. Architectural Framework & Core Innovations](#2-architectural-framework--core-innovations)
  - [Dynamic Copy Budget Management](#dynamic-copy-budget-management)
  - [Multi-Attribute Reconnect Scoring](#multi-attribute-reconnect-scoring)
  - [Tri-Tier Forwarding Policy](#tri-tier-forwarding-policy)
  - [Network-Wide Delivery Acknowledgment (ACK) Purging](#network-wide-delivery-acknowledgment-ack-purging)
  - [Last-Copy "Focus Phase"](#last-copy-focus-phase)
  - [Adaptive Congestion & TTL Gating](#adaptive-congestion--ttl-gating)
  - [Case-Based Reasoning (CBR) Adaptation](#case-based-reasoning-cbr-adaptation)
  - [Utility-Based Buffer Drop Eviction](#utility-based-buffer-drop-eviction)
- [3. Evaluation Metrics & Delivery Cost Formulation](#3-evaluation-metrics--delivery-cost-formulation)
- [4. Benchmark Results Across Network Densities](#4-benchmark-results-across-network-densities)
  - [Comprehensive Comparison Matrix](#comprehensive-comparison-matrix)
  - [Sparse Topology Analysis (51 Nodes)](#sparse-topology-analysis-51-nodes)
  - [Medium Topology Analysis (126 Nodes)](#medium-topology-analysis-126-nodes)
  - [Dense Topology Analysis (246 Nodes)](#dense-topology-analysis-246-nodes)
  - [Computational Complexity & Scalability](#computational-complexity--scalability)
- [5. Repository Structure](#5-repository-structure)
- [6. Getting Started & Installation](#6-getting-started--installation)
  - [Prerequisites](#prerequisites)
  - [Compiling the Codebase](#compiling-the-codebase)
  - [Running the Simulation](#running-the-simulation)
  - [Automated Benchmarking Scripts](#automated-benchmarking-scripts)
- [7. Configuration Parameter Reference](#7-configuration-parameter-reference)
  - [AdaptiveHybridRouter Parameters](#adaptivehybridrouter-parameters)
  - [DeliveryCostReport Parameters](#deliverycostreport-parameters)
- [8. License & Acknowledgments](#8-license--acknowledgments)

---

## 1. Overview & Motivation

Delay-Tolerant Networks (DTNs) operate in challenged environments characterized by intermittent connectivity, frequent link disruptions, substantial propagation delays, and the absence of continuous end-to-end routing paths. In such environments, traditional routing schemes fail completely.

Conventional DTN routing protocols exhibit fundamental trade-offs:
- **Direct Delivery**: Relies exclusively on destination encounter; achieves zero transmission overhead but suffers from abysmal delivery probabilities and intolerable latencies.
- **Epidemic Routing**: Floods copies indiscriminately across all encounters. While effective in sparse, uncongested networks, it causes catastrophic buffer congestion, channel saturation, and massive packet drops in medium-to-dense deployments.
- **PRoPHET**: Uses encounter history and transitivity, but lacks replication quotas, causing severe replication loops and buffer exhaustion under high traffic.
- **MaxProp**: Utilizes Dijkstra-based shortest-path cost estimation and hop-based prioritization, but scales at $\mathcal{O}(N^3)$ computational complexity, causing simulation stalling in dense topologies ($N \ge 246$).

**AdaptiveHybridRouter** addresses these systemic limitations through an intelligent, hybrid protocol design. It dynamically adjusts replication budgets, forwarding aggressiveness, and drop priorities by combining **topological encounter history**, **directional-velocity mobility vectors**, **contact recency**, **congestion awareness**, and **cognitive case-based reasoning (CBR)**.

---

## 2. Architectural Framework & Core Innovations

The protocol implementation is housed in [`src/routing/AdaptiveHybridRouter.java`](file:///src/routing/AdaptiveHybridRouter.java) and incorporates eight synergistic routing mechanisms:

```
                      +-----------------------------------+
                      |   Neighbor Contact Event Detected |
                      +-----------------------------------+
                                        |
                   [Exchange Delivery ACKs & Purge Buffers]
                                        |
           +----------------------------+----------------------------+
           |                                                         |
[Destination-Aware Utility]                               [Mobility & Recency Features]
- PRoPHET Predictability P(a,d)                           - Velocity Consistency (V)
- FRESH Contact Recency (T_fresh)                         - Direction Persistence (D)
           |                                              - Temporal Contact Gap (T)
           +----------------------------+----------------------------+
                                        |
                         +-----------------------------+
                         | Compute Hybrid Score (S)    |
                         | S = α·U_dest + (1-α)·P_mob  |
                         +-----------------------------+
                                        |
                         +-----------------------------+
                         | CBR Policy Audit & Override |
                         +-----------------------------+
                                        |
               +------------------------+------------------------+
               |                        |                        |
       S >= θ_high              θ_low < S < θ_high          S <= θ_low
               |                        |                        |
        [SINGLE Tier]             [TOP_K Tier]              [FLOOD Tier]
      (Large Budget Share)     (Balanced Budget Share)   (Exploratory Gated)
               |                        |                        |
               +------------------------+------------------------+
                                        |
                      +-----------------------------------+
                      |  Forward Message & Update Budget  |
                      | (Focus Phase if Last Copy Remains)|
                      +-----------------------------------+
```

### Dynamic Copy Budget Management
Rather than relying on unbounded replication or static thresholds, each message carries a concrete network-wide copy budget $L$ initialized at message creation (`initialCopies = 6`). When forwarding a message:
- The sender and receiver split the remaining copy budget based on the selected tier.
- A receiver holding copies cannot receive duplicate copies (eliminating redundant `DENIED_OLD` transmission waste).

### Multi-Attribute Reconnect Scoring
Upon encountering neighbor node $k$, the router evaluates a multi-attribute probability score $P$:

$$P = w_V V + w_D D + w_H H + w_T T$$

Where:
- **Velocity Consistency ($V$)**: Ratio of speed stability $\min(v_{\text{cur}}, v_{\text{prev}}) / \max(v_{\text{cur}}, v_{\text{prev}})$ ensuring sustained contact.
- **Direction Persistence ($D$)**: Cosine similarity between successive motion vectors verifying directional heading toward target interaction zones.
- **Historical Predictability ($H$)**: PRoPHET encounter predictability $P_{(a, k)}$.
- **Temporal Recency ($T$)**: Exponential recency decay based on the inter-contact gap $\exp(-\Delta t_{\text{gap}} / \tau_{\text{rec}})$.

The weights adaptively adjust according to the locally perceived node density fraction $d_f$:

$$w_V = 0.30 - 0.10 d_f, \quad w_D = 0.20 + 0.10 d_f, \quad w_H = 0.20 + 0.10 d_f, \quad w_T = 0.30 - 0.10 d_f$$

### Tri-Tier Forwarding Policy
The router blends destination utility $U_{\text{dest}}$ (fusing PRoPHET predictability $P(k, \text{dest})$ and FRESH contact recency) with $P$:

$$S = \alpha \cdot U_{\text{dest}} + (1 - \alpha) \cdot P$$

Based on $S$, the protocol selects an operating tier:
1. **`SINGLE` Tier ($S \ge \theta_{\text{high}} = 0.70$)**: High confidence encounter. Allocates a large fraction of copies (`shareSingle = 0.70`) directly to this promising carrier.
2. **`TOP_K` Tier ($\theta_{\text{low}} < S < \theta_{\text{high}}$)**: Moderate confidence. Allocates a balanced quota (`shareTopK = 0.50`).
3. **`FLOOD` Tier ($S \le \theta_{\text{low}} = 0.25$)**: Low confidence / exploratory. Allocates a small fraction (`shareFlood = 0.25`), subject to adaptive gating.

### Network-Wide Delivery Acknowledgment (ACK) Purging
Upon establishing contact, nodes exchange bitsets of delivered message IDs (`deliveredIds`). If a node possesses a message already confirmed as delivered, the message is immediately removed from the buffer, preventing useless downstream relaying and freeing valuable buffer capacity.

### Last-Copy "Focus Phase"
When a message's remaining copy budget decrements to $L = 1$, the router enters the **Focus Phase**. The message will only be forwarded if the candidate neighbor demonstrates a clear utility advantage over the current host:

$$U_{\text{dest}}(\text{neighbor}) - U_{\text{dest}}(\text{current}) \ge \Delta_{\text{focus}} \quad (\Delta_{\text{focus}} = 0.05)$$

### Adaptive Congestion & TTL Gating
Exploratory `FLOOD`-tier spraying is strictly suppressed when:
- Local buffer occupancy exceeds `floodOccupancyLimit` ($0.75$).
- Message remaining TTL fraction drops below `minTtlFracFlood` ($0.20$).

### Case-Based Reasoning (CBR) Adaptation
An embedded cognitive layer maintains an episodic case memory of $(c, \text{tier}, \text{outcome})$ tuples. For borderline decisions, the router queries the $k$-nearest historical cases in Euclidean feature space to validate or override the analytical tier decision, continually optimizing delivery yield versus transmission cost.

### Utility-Based Buffer Drop Eviction
When buffer overflow occurs, messages are evaluated by retention utility:

$$\text{Retention Priority} = U_{\text{dest}} \cdot \left(\frac{\text{TTL}_{\text{remaining}}}{\text{TTL}_{\text{initial}}}\right)$$

Messages with the lowest retention priority are evicted first, protecting high-probability, active messages.

---

## 3. Evaluation Metrics & Delivery Cost Formulation

Performance and resource expenditures are quantified using the metric framework implemented in [`src/report/DeliveryCostReport.java`](file:///src/report/DeliveryCostReport.java) (see formal derivations in [`reports/Delivery_Cost_Formulation_Report.md`](file:///reports/Delivery_Cost_Formulation_Report.md)):

### 1. Transmission Delivery Cost ($C_{\text{tx}}$)
The average number of packet forwardings expended across the entire network per successfully delivered message:

$$C_{\text{tx}} = \frac{M_{\text{relayed}}}{M_{\text{delivered}}} = 1 + \text{Overhead Ratio}$$

### 2. Cost-Performance Index ($\text{CPI}$)
The delivery yield generated per unit of transmission cost:

$$\text{CPI} = \left(\frac{\text{Delivery Probability}}{C_{\text{tx}}}\right) \times 100$$

### 3. Composite Resource Cost ($C_{\text{comp}}$)
A multi-dimensional cost metric weighting packet volume ($w_V = 0.40$), delivery delay ($w_D = 0.25$), hop length ($w_H = 0.15$), and buffer residency duration ($w_T = 0.20$):

$$C_{\text{comp}} = w_V \left(\frac{C_{\text{tx}}}{C_{\text{tx}}^{\max}}\right) + w_D \left(\frac{\bar{T}_{\text{delay}}}{\text{TTL}}\right) + w_H \left(\frac{\bar{H}}{H^{\max}}\right) + w_T \left(\frac{\bar{T}_{\text{buffer}}}{\text{TTL}}\right)$$

---

## 4. Benchmark Results Across Network Densities

The evaluation was conducted on the standard Helsinki city model ($4500 \times 3400\,\text{m}$) over 43,200 simulated seconds (12.0 hours) across three rigorous density scenarios:
- **Sparse**: 51 nodes ($3.33\,\text{nodes/km}^2$) — High isolation, rare contacts.
- **Medium**: 126 nodes ($8.24\,\text{nodes/km}^2$) — Standard benchmark traffic.
- **Dense**: 246 nodes ($16.08\,\text{nodes/km}^2$) — Severe buffer contention and high contact frequency.

### Comprehensive Comparison Matrix

| Scenario | Nodes | Protocol | Delivered / Created | Delivery Rate (%) | Overhead Ratio | Avg Latency (s) | Dropped Packets | $C_{\text{tx}}$ | CPI | Composite Cost |
| :--- | :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Sparse** | **51** | **AdaptiveHybridRouter** | **435 / 1466** | **29.67%** | **9.13** | **5,187.9** | **3,772** | **10.13** | **2.9302** | **0.2202** |
| Sparse | 51 | MaxPropRouter | 428 / 1466 | 29.20% | 9.88 | 6,764.8 | 4,200 | 10.88 | 2.6843 | 0.2513 |
| Sparse | 51 | ProphetRouter | 388 / 1466 | 26.47% | 12.38 | 4,625.0 | 5,672 | 13.38 | 1.9782 | 0.2507 |
| Sparse | 51 | EpidemicRouter | 350 / 1466 | 23.87% | 13.55 | 4,799.6 | 5,619 | 14.55 | 1.6413 | 0.2648 |
| Sparse | 51 | DirectDeliveryRouter | 298 / 1466 | 20.33% | 0.00 | 4,091.5 | 1,140 | 1.00 | 20.3274 | 0.1787 |
| **Medium** | **126** | **AdaptiveHybridRouter** | **822 / 1463** | **56.19%** | **19.94** | **4,903.8** | **12,442** | **20.94** | **2.6833** | **0.3096** |
| Medium | 126 | MaxPropRouter | 821 / 1463 | 56.12% | 27.88 | 6,044.5 | 19,091 | 28.88 | 1.9433 | 0.4033 |
| Medium | 126 | DirectDeliveryRouter | 472 / 1463 | 32.26% | 0.00 | 6,296.7 | 925 | 1.00 | 32.2625 | 0.2862 |
| Medium | 126 | ProphetRouter | 402 / 1463 | 27.48% | 66.36 | 4,660.5 | 27,029 | 67.36 | 0.4079 | 0.5938 |
| Medium | 126 | EpidemicRouter | 374 / 1463 | 25.56% | 84.80 | 4,272.9 | 32,096 | 85.80 | 0.2979 | 0.5866 |
| **Dense** | **246** | **AdaptiveHybridRouter** | **1,200 / 1465** | **81.91%** | **33.99** | **3,640.8** | **25,605** | **34.99** | **2.3407** | **0.4235** |
| Dense | 246 | DirectDeliveryRouter | 548 / 1465 | 37.41% | 0.00 | 6,782.1 | 861 | 1.00 | 37.4061 | 0.3204 |
| Dense | 246 | ProphetRouter | 385 / 1465 | 26.28% | 251.65 | 4,212.0 | 96,506 | 252.65 | 0.1040 | 0.5786 |
| Dense | 246 | EpidemicRouter | 342 / 1465 | 23.34% | 359.66 | 3,885.7 | 122,316 | 360.66 | 0.0647 | 0.5723 |
| Dense | 246 | MaxPropRouter | *Failed* | *N/A* | *N/A* | *N/A* | *N/A* | *N/A* | *N/A* | *N/A* |

### Sparse Topology Analysis (51 Nodes)
- **Rank #1 Performer**: **AdaptiveHybridRouter** achieves **29.67% delivery rate** (435 messages), outperforming MaxProp (29.20%), PRoPHET (26.47%), and Epidemic (23.87%).
- Delivers messages **1,576.9 seconds (~26.3 minutes) faster than MaxProp** while lowering dropped packets from 4,200 to 3,772.

### Medium Topology Analysis (126 Nodes)
- **AdaptiveHybridRouter** delivers **56.19%** (822 messages), matching and exceeding MaxProp (56.12%) while reducing transmission overhead by **28.5%** (19.94 vs. 27.88) and slashing packet drops by **34.8%** (12,442 vs. 19,091).
- Epidemic and PRoPHET collapse due to buffer thrashing, dropping over 27,000–32,000 packets with delivery below 28%.

### Dense Topology Analysis (246 Nodes)
- **Dominant Performance**: AdaptiveHybridRouter scales cleanly to **81.91% delivery rate (1,200/1,465 messages)** with an overhead of only 33.99.
- **Flooding Protocols Collapse**: Epidemic suffers routing meltdown, dropping 122,316 packets with an overhead ratio of **359.66** and delivering only 23.34%—worse than its sparse performance! PRoPHET drops 96,506 packets with overhead 251.65.
- **AdaptiveHybrid delivers 3.5x more messages** than Epidemic and PRoPHET with 10x less network overhead.

### Computational Complexity & Scalability
In the Dense scenario (246 nodes), **MaxPropRouter failed to execute to completion**, stalling at 3.28 simulated seconds per real-time second (~2.6 hours for a single run) due to all-pairs Dijkstra path recalculation ($\mathcal{O}(N^3)$). In contrast, **AdaptiveHybridRouter computes routing decisions in $\mathcal{O}(1)$ time per encounter**, completing a 12-hour simulation in under 35 seconds.

---

## 5. Repository Structure

```
├── configs/                                 # Simulation scenario configuration profiles
│   ├── AdaptiveHybridRouter.txt             # Baseline AdaptiveHybridRouter configuration
│   ├── density_Sparse_*.txt                 # 51-node sparse topology configurations
│   ├── density_Medium_*.txt                 # 126-node medium topology configurations
│   ├── density_Dense_*.txt                  # 246-node dense topology configurations
│   └── ...                                  # Comparative router configs (Epidemic, PRoPHET, MaxProp)
├── data/                                    # Map WKT vector data and movement paths
│   ├── roads.wkt, shops.wkt, etc.           # Helsinki map layers
├── lib/                                     # Required simulation runtime libraries
│   ├── DTNConsoleConnection.jar
│   └── ECLA.jar
├── reports/                                 # Benchmark output logs and synthesized datasets
│   ├── All_Routers_Comparison_Summary.csv   # Unified benchmark metrics across all routers
│   ├── Density_Scenarios_Comparison.csv     # Metrics across Sparse, Medium, and Dense tests
│   └── Delivery_Cost_Formulation_Report.md  # Detailed mathematical metric formulations
├── src/
│   ├── routing/
│   │   ├── AdaptiveHybridRouter.java        # Core Adaptive Hybrid Routing Protocol
│   │   ├── EpidemicRouter.java, etc.        # Reference baseline DTN routers
│   ├── report/
│   │   ├── DeliveryCostReport.java          # C_tx, CPI, and composite cost report
│   │   └── MessageStatsReport.java, etc.    # Default ONE simulator reports
├── default_settings.txt                     # Main simulator configuration file
├── density_scenarios_benchmark_report.md    # Full experimental benchmark report
├── compile.bat / compile.sh                 # Compilation scripts for Windows & Linux/macOS
├── one.bat / one.sh                         # Simulator launch scripts
├── run_all_routers.ps1                      # Automated batch runner for all standard routers
├── run_density_experiments.ps1              # Density experiment batch execution engine
└── parse_density_results.ps1                # Automated result aggregator and CSV generator
```

---

## 6. Getting Started & Installation

### Prerequisites
- **Java Development Kit (JDK)**: JDK 8, 11, or 17+ installed with `javac` and `java` in your system `PATH`.
- **PowerShell 5.1+** (for Windows automated scripts) or Bash (for Unix/macOS).
- **Git**.

### Compiling the Codebase

**Windows**:
```cmd
compile.bat
```

**Linux / macOS**:
```bash
chmod +x compile.sh one.sh
./compile.sh
```

### Running the Simulation

#### Launch with Graphical User Interface (GUI)
```cmd
one.bat
```

#### Launch Headless Batch Run
To run `AdaptiveHybridRouter` using default settings:
```cmd
one.bat -b 1 default_settings.txt
```

To run a specific density scenario:
```cmd
one.bat -b 1 configs/density_Dense_AdaptiveHybridRouter.txt
```

### Automated Benchmarking Scripts

#### 1. Run Complete Benchmark Suite Across Node Densities
Executes Sparse, Medium, and Dense scenarios across DirectDelivery, Epidemic, PRoPHET, MaxProp, and AdaptiveHybridRouter:
```powershell
.\run_density_experiments.ps1
```

#### 2. Parse Metrics and Generate CSV Summaries
Parses raw report logs from `reports/` and outputs formatted markdown tables and CSVs:
```powershell
.\parse_density_results.ps1
```

#### 3. Run All Baseline Routers
Runs comparison tests across all available DTN routing protocols:
```powershell
.\run_all_routers.ps1
```

---

## 7. Configuration Parameter Reference

### AdaptiveHybridRouter Parameters

Parameters are configured under the `AdaptiveHybridRouter.` namespace in scenario configuration files:

| Setting Key | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `highThreshold` | `double` | `0.70` | Threshold $S \ge \theta_{\text{high}}$ for selecting `SINGLE` tier. |
| `lowThreshold` | `double` | `0.25` | Threshold $S \le \theta_{\text{low}}$ for selecting `FLOOD` tier. |
| `initialCopies` | `int` | `6` | Initial network-wide copy budget $L$ per generated message. |
| `shareSingle` | `double` | `0.70` | Fraction of remaining copies delegated in `SINGLE` tier. |
| `shareTopK` | `double` | `0.50` | Fraction of remaining copies delegated in `TOP_K` tier. |
| `shareFlood` | `double` | `0.25` | Fraction of remaining copies delegated in `FLOOD` tier. |
| `focusPhase` | `boolean` | `true` | Enables utility-constrained handover for the final remaining copy ($L=1$). |
| `focusMargin` | `double` | `0.05` | Required utility advantage over current host to permit focus handover. |
| `alpha` | `double` | `0.65` | Weight of destination utility vs. mobility probability score in $S$. |
| `wPred` | `double` | `0.60` | Weight of PRoPHET predictability vs. FRESH contact recency in $U_{\text{dest}}$. |
| `freshDecay` | `double` | `3600.0` | Half-life decay constant (seconds) for FRESH contact recency. |
| `recencyDecay`| `double` | `1800.0` | Decay constant (seconds) for neighbor contact gap recency. |
| `floodOccupancyLimit` | `double` | `0.75` | Maximum buffer occupancy ratio above which `FLOOD` spraying is suppressed. |
| `minTtlFracFlood` | `double` | `0.20` | Minimum message remaining TTL fraction required to permit `FLOOD` spraying. |
| `useCBR` | `boolean` | `true` | Enables Case-Based Reasoning episodic memory optimization. |
| `cbrK` | `int` | `5` | Number of nearest neighbors evaluated during CBR retrieval. |
| `cbrMinCases` | `int` | `10` | Minimum case count required before CBR overrides are permitted. |
| `cbrMaxCases` | `int` | `300` | Maximum cases retained in memory (oldest evicted via sliding window). |
| `debugStats` | `boolean` | `false` | Outputs aggregate routing tier distribution upon simulation shutdown. |

### DeliveryCostReport Parameters

Configured under the `DeliveryCostReport.` namespace:

| Setting Key | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `weightV` | `double` | `0.40` | Volume / Transmission cost weight in composite index ($w_V$). |
| `weightD` | `double` | `0.25` | Latency / Delay weight in composite index ($w_D$). |
| `weightH` | `double` | `0.15` | Hop count weight in composite index ($w_H$). |
| `weightT` | `double` | `0.20` | Buffer residency duration weight in composite index ($w_T$). |
| `maxCostNorm` | `double` | `50.0` | Normalization ceiling for transmission delivery cost ($C_{\text{tx}}^{\max}$). |

---

