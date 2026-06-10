(ns hotel.booking.room.fsm
  "The room stream as an explicit FINITE STATE MACHINE.
   Per thinkbeforecoding's Decider article: state is explicit states,
   not boolean flags, and disposal (terminal state) is designed up front.

                 RoomBooked                BookingCancelled
   :available ──────────────► :booked ──────────────────────► :available
       │
       │ RoomDecommissioned
       ▼
   :decommissioned   ◄── TERMINAL: no command is ever accepted again

   Three slices (book-room, cancel-booking, decommission-room) compose
   their deciders over this ONE shared kernel: one aggregate, several
   command-specific deciders. NOTE: an aggregate's state need not be an
   FSM - accumulative data + invariants (seat sets, balances) is equally
   valid when truthful. This one is modal, so an FSM fits.")

(def initial-state {:status :available})

(defn evolve
  "PURE. (state, event) -> state. Just moves the machine; no decisions here."
  [state event]
  (case (:event/type event)
    :room-booked         (assoc state :status :booked :guest (:guest event))
    :booking-cancelled   (assoc state :status :available :guest nil)
    :room-decommissioned (assoc state :status :decommissioned)
    state))

(defn terminal?
  "isTerminal: state -> bool. The 7th element of the Decider."
  [state]
  (= :decommissioned (:status state)))
