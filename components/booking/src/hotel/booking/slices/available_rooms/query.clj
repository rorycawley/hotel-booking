(ns hotel.booking.slices.available-rooms.query
  "Impure shell: reads the CQRS room VIEW (a Postgres table in prod, an
   atom in tests) and filters down to bookable rooms.

   Catch-up policy: a query advances the view by at most ONE batch
   (`sync-batch`). Beyond that the background projector catches up
   asynchronously - we don't fold the world synchronously, ever. If the
   projection is more than `lag-threshold` events behind AFTER the sync
   catch-up, we record a problem so ops sees that queries are stale."
  (:require [hotel.event-store.interface :as es]
            [hotel.problems.interface :as problems]
            [hotel.booking.slices.available-rooms.projection :as proj]))

(def ^:private projection-name "rooms")
(def ^:private sync-batch     1000)
(def ^:private lag-threshold  1000)

(defn handle [{:keys [event-store all-room-ids problem-sink]}]
  ;; One bounded catch-up step per query. The background projector
  ;; component drains the rest.
  (es/advance-room-view! event-store projection-name proj/evolve sync-batch)
  (let [lag (es/projection-lag event-store projection-name)]
    (when (and problem-sink (> lag lag-threshold))
      (problems/record-problem!
       problem-sink
       {:problem :room-view-stale-after-sync-catchup
        :projection-name projection-name
        :lag lag
        :threshold lag-threshold})))
  (proj/available (es/read-room-view event-store) all-room-ids))
