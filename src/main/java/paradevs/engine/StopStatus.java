package paradevs.engine;

/**
 * Result of StopStatus at a persistent entry (main_paratemporal, Section 2.3). The tests are
 * applied in this order: horizon (n = m), acceptor, then passive (infinite time advance and no
 * inputs left). An infinite time advance alone is not terminal while inputs remain.
 */
public enum StopStatus { LIVE, HORIZON, ACCEPT, PASSIVE }
