(ns hotel.clock.system-clock
  "PRODUCTION adapter: the host clock plus a LIVE max-error bound.

   Sources of `uncertainty-ms`:

     1. STATIC (default): a constant passed at construction. Useful when
        the platform's clock-quality service is unavailable; the value
        should be the WORST-case error the install tolerates (e.g. 50ms
        for NTP over WAN; 5ms for chrony on a good LAN).

     2. DYNAMIC: pass `:uncertainty-fn` returning the live max-error in
        milliseconds. Read from chrony's `Maximum error` (`chronyc
        tracking`), AWS ClockBound, a PTP servo offset metric -
        whatever the platform exposes.

   When the live error exceeds `:degraded-threshold-ms`, the adapter
   calls `on-degraded` (typically the ProblemSink) so ops sees that the
   journal's evidence quality is degraded. Ordering is still by log
   position (ADR-0001) - degraded clocks never affect correctness, only
   evidence quality.

   For court use, document the traceability chain to UTC (NIST/national
   time service) - the chain MUST be a deployment artefact, not just
   in code."
  (:require [hotel.clock.protocol :as clock]))

(defrecord SystemClock [uncertainty-ms uncertainty-fn
                        degraded-threshold-ms on-degraded]
  clock/Clock
  (now-interval [_]
    (let [now (System/currentTimeMillis)
          u   (or (try (and uncertainty-fn (uncertainty-fn))
                       (catch Throwable _ nil))
                  uncertainty-ms
                  0)]
      (when (and on-degraded degraded-threshold-ms
                 (>= u degraded-threshold-ms))
        (try (on-degraded {:uncertainty-ms u
                           :threshold-ms   degraded-threshold-ms})
             (catch Throwable _ nil)))
      {:earliest (- now u)
       :latest   (+ now u)})))

(defn create
  "config keys (all optional except uncertainty-ms when no -fn):
     :uncertainty-ms        - static fallback (also a default)
     :uncertainty-fn        - 0-arg fn returning live max-error (ms)
     :degraded-threshold-ms - call on-degraded when uncertainty >= this
     :on-degraded           - 1-arg fn called with {:uncertainty-ms ..
                              :threshold-ms ..} when threshold crossed"
  [{:keys [uncertainty-ms uncertainty-fn degraded-threshold-ms on-degraded]
    :or {uncertainty-ms 0}}]
  (->SystemClock uncertainty-ms uncertainty-fn
                 degraded-threshold-ms on-degraded))
