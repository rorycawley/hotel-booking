(ns hotel.clock.system-clock
  "PRODUCTION adapter: the host clock plus a configured error bound.
   HONEST LIMITATION: :uncertainty-ms is static here. A real deployment
   reads the LIVE bound from its sync daemon (chrony tracking / PTP
   servo offset / AWS ClockBound) so degraded sync widens the recorded
   intervals automatically. Choose the bound to match the install:
   NTP over WAN ~ a few ms; chrony on a good LAN ~ sub-ms; PTP with
   GNSS grandmaster + hardware timestamping ~ microseconds. Document
   the traceability chain to UTC and monitor divergence (MiFID RTS 25
   is the template for timestamps that must survive scrutiny)."
  (:require [hotel.clock.protocol :as clock]))

(defrecord SystemClock [uncertainty-ms]
  clock/Clock
  (now-interval [_]
    (let [now (System/currentTimeMillis)]
      {:earliest (- now uncertainty-ms)
       :latest   (+ now uncertainty-ms)})))

(defn create [{:keys [uncertainty-ms]}]
  (->SystemClock uncertainty-ms))
