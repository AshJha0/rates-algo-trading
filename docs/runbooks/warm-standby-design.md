# Warm-standby design over Aeron

Status: design, not built. The parts that can be built without the P2 event loop exist (the restart procedure, the journal replay, the venue status hooks, the blue/green switch); the replication channel is built at integration. This note says what the standby holds, how state is handed over, and how a takeover avoids two processes quoting at once.

## Plain idea

The standby runs the same binary as the active process and reads the active process's journal as it is written, applying every frame to its own state. Because the event loop is deterministic (one writer, injected clock, no allocation), applying the same frames produces the same state. When the active process fails, the standby already has the state up to the last frame it received; it needs only the frames it missed, and the venues' view of our orders and quotes, before it can quote.

## Formal statement

- **Replication.** The active process publishes each journal frame, after appending it, on an Aeron publication (UDP unicast to the standby host, or IPC on one host for tests). The standby subscribes, appends each frame to its own journal (the same RAJ1 format, so sequence and chain hash are checked frame by frame) and drives its handlers in replay mode with a `ManualClock`.
- **What is handed over.** Nothing beyond the journal. Positions, open orders, live quotes, risk, timers and the quoting state are functions of the frames. This is the reason the platform journals inputs, not state.
- **Lag.** The standby reports its last applied sequence on a reverse Aeron channel; the active process exports the difference as a metric. A standby more than N frames behind is not eligible to take over without catching up first.
- **Fencing.** Only one process may hold the quoting lease. The lease is a record with an epoch number held by the control plane (a single-node store in this project; a consensus store such as Aeron Cluster on a desk). The active process renews it every second; a process that cannot renew stops quoting by itself (kill switch, reason SessionLoss) before the lease expires. The standby acquires the lease only after expiry, incrementing the epoch, and every outbound order carries the epoch in its client order id range, so a venue message from the old epoch is recognisable.
- **Takeover.** On acquiring the lease the standby runs the restart procedure (docs/runbooks/standby-takeover.md): quoting disabled, journal applied to its end, frames the active published but the standby never received fetched from the active's journal if the host is reachable or from the venues' drop copies if not, venue order and quote status reconciled, quoting enabled only if nothing blocks.

## Worked example

The active process fails at journal sequence 1,204,331. The standby has applied 1,204,329; two frames were in flight. The lease expires one second after the last renewal; the standby takes it with epoch 18. Its restart procedure finds one order at venue 1 with a cumulative quantity 50 above the journal's: the fill in the missing frames. The procedure blocks quoting and reports it. Operations obtains the execution report from the drop copy, it is appended, the procedure runs again, finds no discrepancy, and enables quoting. Time from failure to quoting is dominated by the lease expiry and the venue status round trips, not by state transfer.

## Why this design

- A state snapshot handed over at failure time is the snapshot taken by a process that is failing. A journal replayed by a healthy process is not.
- The same mechanism serves recovery from a crash on one host (replay the local journal), a planned blue/green switch, and a disaster-recovery site (replicate over a WAN link with a longer lag budget).
- RTS 6 Article 14 asks for business continuity arrangements proportionate to the business, tested; the rehearsal and the restart procedure tests are the start of that evidence.

## Open items for integration

- Aeron publication and subscription in the P2 event loop, with the media driver embedded or external (decision record at integration).
- The lease store and the epoch in client order ids (P2 gateway, P3 quoting).
- A failover test: kill the active process mid-burst in the simulated environment, check that no order or quote is sent under two epochs and that the standby quotes within the target time.
