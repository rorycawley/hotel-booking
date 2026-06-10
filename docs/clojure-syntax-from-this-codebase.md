# Clojure syntax used in this codebase — a training reference

A from-scratch tour of **every Clojure construct** that appears in the hotel-booking
code and tests. Each entry is: one plain-English line, then a **real snippet from this
repo**, then what it does.

## Scope (read this first)

- **Fully covered:** all application code under `components/`, `bases/`, `projects/`,
  `development/`, and all test files. If a construct appears there, it's in the main body.
- **Appendix B** covers constructs that appear *only* in the vendored tooling files
  under `.clj-kondo/imports/` (third-party hook code for `potemkin` and `hiccup`). Those
  are not part of the hotel system, but they're in the repo, so they're listed there to
  make this genuinely 100%.
- **Appendix A** is a short note on the `deps.edn` / `workspace.edn` files: those are
  **EDN data**, not evaluated Clojure, but they use the same literal syntax.

How to use it: skim the headings, stop where something's unfamiliar. Every example is
real, so you can grep the filename to see it in context.

---

# 1. Namespaces and loading code

## `ns` — declare a namespace

Every file starts with one. It names the namespace and lists what it pulls in.

```clojure
(ns hotel.rest-api.main
  "Entry point of the ONE deployable unit. ..."   ; <- docstring
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [com.stuartsierra.component :as component]
            [hotel.system.interface :as system]
            [hotel.rest-api.routes :as routes])
  (:gen-class))
```

- The string after the name is the namespace **docstring** (optional, common here).
- `:require` loads other namespaces.
- `:gen-class` (bottom) tells the compiler to generate a real Java class — needed because
  this namespace has a `-main` and gets compiled into the runnable jar.

## `:require` with `:as` — load and give it a short alias

```clojure
(:require [hotel.booking.interface :as booking]
          [hotel.event-store.interface :as es])
```

Now `booking/book-room!` and `es/read-stream` refer to functions in those namespaces.
The `/` separates the alias from the name.

## Two aliases for one namespace

You can alias the same namespace twice when you want different reading in different spots:

```clojure
;; ordering_test.clj
(:require [hotel.clock.interface :as clock]
          [hotel.clock.interface :as det-clock])
```

Both point at the same code; `clock/overlapping?` and `det-clock/set-time!` just read
differently at the call site.

## `:refer` — pull specific names in unqualified

```clojure
(:require [clojure.test :refer [deftest is]])
```

Now you write `deftest` and `is` directly, without a `clojure.test/` prefix. Use this
sparingly; `:as` is the default style in this codebase. (`:refer :all` is banned here —
see `CLAUDE.md`.)

## `:import` — bring in a Java class

```clojure
;; decider.clj
(:import [clojure.lang ExceptionInfo])

;; postgres.clj
(:import [java.sql SQLException])
```

Java classes aren't namespaces; `:import` makes the bare class name available
(`ExceptionInfo`, `SQLException`) for `catch` and type hints.

## `require` at runtime (in the REPL)

Inside a running REPL you can require on the fly. Note the **quote** `'`:

```clojure
;; user.clj comment block
(require '[clojure.test :as t])
```

The `'` stops Clojure from evaluating `[clojure.test :as t]` as code — `require` wants the
literal vector, not its value. (More on quoting in §18.)

---

# 2. Defining things

## `def` — bind a name to a value

```clojure
;; app.clj
(def reactors [confirm/react announce/react move-pm/react])

;; book_room/decide.clj
(def command-schema
  [:map
   [:room-id   :string]
   [:guest     events/Guest]
   [:check-in  :string]
   [:check-out :string]])
```

`def` creates a top-level **var**. The value can be anything — a vector of functions, a
schema (which is just data), a map.

## `defn` — define a function

```clojure
;; fsm.clj
(defn evolve
  "PURE. (state, event) -> state. Just moves the machine; no decisions here."
  [state event]
  (case (:event/type event)
    :room-booked         (assoc state :status :booked :guest (:guest event))
    :booking-cancelled   (assoc state :status :available :guest nil)
    :room-decommissioned (assoc state :status :decommissioned)
    state))
```

Shape: `(defn name "docstring" [params] body)`. The last expression is the return value —
no `return` keyword.

## `defn-` — define a *private* function

The trailing `-` makes it private to the namespace (callers in other namespaces can't use it).

```clojure
;; main.clj
(defn- env! [k]
  (or (System/getenv k)
      (throw (ex-info (str "Missing required environment variable: " k) {:var k}))))

;; routes.clj
(defn- segments [uri] (vec (remove str/blank? (str/split uri #"/"))))
```

This codebase uses `defn-` heavily for helpers and only exposes the real API.

## Multi-arity functions — one name, several parameter lists

Each `([params] body)` is one arity. Clojure picks by argument count.

```clojure
;; deterministic.clj
(defn create
  ([] (create {:now 1750000000000 :uncertainty-ms 0}))   ; 0 args -> calls the 1-arg version
  ([init] (->DeterministicClock (atom init))))            ; 1 arg
```

```clojure
;; clock/interface.clj
(defn deterministic
  ([] (deterministic/create))
  ([init] (deterministic/create init)))
```

## `declare` — promise a name now, define it later

Used when two functions call each other (mutual recursion) and one is defined below.

```clojure
;; effects.clj
(declare react-all!)
;; ... execute! calls react-all! ...
;; ... react-all! is defined further down
```

Without `declare`, the compiler would complain that `react-all!` is unknown when it first
sees it inside `execute!`.

## `defonce` — define once, don't clobber on reload

```clojure
;; user.clj
(defonce the-system nil)
```

Unlike `def`, reloading the namespace won't reset `the-system` back to `nil`. Used to keep
a running system alive across REPL reloads.

## `deftest` — define a test (it's a `def` form too)

```clojure
;; book_room_test.clj
(deftest an-available-room-can-be-booked
  (is (= {:events [{:event/type :room-booked ...}]}
         (decider/decide book/decider [] book-cmd))))
```

Covered properly in §17.

---

# 3. Data literals

Clojure code *is* data. These literals appear constantly.

## Maps `{}`

Key/value pairs. Keys are usually keywords.

```clojure
;; fsm.clj
(def initial-state {:status :available})

;; book_room_test.clj
(def book-cmd
  {:room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}   ; nested map
   :check-in "2026-07-01" :check-out "2026-07-03"})
```

## Vectors `[]`

Ordered, indexed. Used for lists of things and for parameter lists.

```clojure
;; system/core.clj
:all-room-ids ["101" "102" "103"]
```

## Lists `()`

In code, a list is a **function or macro call** — the first element is what's called:

```clojure
(assoc state :status :booked)   ; call assoc with 3 args
```

You rarely write a *literal* list of data; when you need one you quote it (see §18) or use
the `list` function.

## Sets `#{}`

Unordered, unique. Great for membership tests.

```clojure
;; process.clj
(defn terminal? [state] (contains? #{:completed :failed} (:status state)))

;; available_rooms/projection.clj
(remove #(contains? #{:booked :decommissioned} (view %)) all-room-ids)
```

## Keywords `:foo`

Self-evaluating labels, mostly used as map keys and enum-like values.

```clojure
:status   :available   :room-booked   :error
```

## Namespaced keywords `:ns/name`

A keyword can carry a namespace, written with `/`. Used here to avoid collisions and to
mark domain meaning.

```clojure
:event/type        ; events.clj — the "type" key in the "event" namespace
:command/type      ; process.clj
:effect/type       ; effects.clj
:hotel.event-store/error   ; in_memory.clj — fully-qualified
```

## `::` — auto-namespaced keyword

`::name` expands to `:current-namespace/name`. Used in tests as throwaway placeholder values.

```clojure
;; book_room_test.clj
(decider/handle book/decider
                {:event-store ::store :clock ::clock}   ; ::store = :hotel.booking.book-room-test/store
                "room-101"
                book-cmd)
```

## Strings, numbers, `nil`, booleans

```clojure
"ada@example.com"      ; string (double quotes only)
1750000000000          ; long
3000                   ; int
nil                    ; nothing / absence
true  false            ; booleans
```

## Regex `#"..."`

A regular-expression literal.

```clojure
;; events.clj — email check
[:email [:re #"^[^@\s]+@[^@\s]+$"]]

;; routes.clj — split a URI on slashes
(str/split uri #"/")

;; user.clj — match test namespaces
(t/run-all-tests #"booking\..*-test|adapters\..*-test")
```

---

# 4. Functions and anonymous functions

## `fn` — an anonymous function

```clojure
;; main.clj
(defn app [system]
  (wrap-params (fn [request] (routes/handler system request))))
```

`(fn [args] body)` is a function with no name. Here it captures `system` and waits for a
`request`.

## `#(...)` — the short anonymous-function reader macro

`#(...)` is shorthand for `fn`. `%` is the first argument, `%1 %2` are positional.

```clojure
;; decider.clj — % is each event
(mapv #(assoc % :recorded-at (clock/now-interval clock)) (:events result))

;; effects.clj — % is each effect
(comp error-result #(execute! system %))

;; projection.clj — % is each room-id
(remove #(contains? #{:booked :decommissioned} (view %)) all-room-ids)

;; user.clj — % is the system; some-> threads it
(alter-var-root #'the-system #(some-> % component/stop))
```

Note `#'the-system` in that last line: `#'name` is a **var reference** (the box, not its
current value) — `alter-var-root` needs the box so it can change what's inside.

---

# 5. `let` and destructuring

## `let` — local bindings

```clojure
;; decider.clj
(defn handle [decider {:keys [event-store clock]} stream-id command]
  (let [history (es/read-stream event-store stream-id)
        result  (decide decider history command)]
    (if (:error result)
      result
      ...)))
```

`let` introduces names that exist only inside its body. Bindings are a flat vector of
`name value name value`, evaluated top to bottom (later ones can use earlier ones).

## Associative destructuring `{:keys [...]}`

Pull keys out of a map into locals named after the keys.

```clojure
;; effects.clj — execute! receives a system map, grabs three keys
(defn execute! [{:keys [guest-notifications publisher command-handlers] :as system} effect]
  ...)
```

`:as system` *also* keeps the whole map bound to `system`.

## Namespaced-key destructuring `{:ns/keys [...]}`

When keys are namespaced, use `:ns/keys`:

```clojure
;; process.clj — :command/type comes out as local `type`
(fn [state {:command/keys [type] :as command}]
  (case type ...))
```

## Sequential destructuring `[a b & rest]`

Pull items out of a vector/seq by position. `&` captures the rest.

```clojure
;; routes.clj — first three URL segments
(let [[root id task] (segments uri)] ...)

;; effects.clj — a result with two events, e.g. tests
(let [[e1 e2] (es/read-all (:event-store sys))] ...)

;; main.clj — ignore all args
(defn -main [& _] ...)            ; & _ = "any number of args, don't care"
```

## Rest args in functions `& body`

```clojure
;; with-redefs in book_room_test — fn taking any args
es/append-events! (fn [& _] (throw (ex-info "Concurrency conflict" ...)))
```

---

# 6. Flow control

## `if`

```clojure
;; decider.clj
(if (:error result)
  result                ; then
  (let [...] ...))      ; else
```

`if` is `(if test then else)`. Exactly one of the branches runs.

## `when` — `if` with no else, allows multiple body forms

```clojure
;; send_confirmation/react.clj
(defn react [event]
  (when (= :room-booked (:event/type event))
    [{:effect/type :notify-booking-confirmed ...}]))
```

If the test is falsey, `when` returns `nil`.

## `when-let` — bind, then run only if truthy

```clojure
;; effects.clj
(when-let [success (:on-success effect)]
  (execute! system {:effect/type :dispatch-command :command success}))
```

Binds `success` to `(:on-success effect)`; runs the body only if that wasn't `nil`/`false`.

## `if-let` — like `when-let` but with an else branch

```clojure
;; interface.clj
(if-let [effect-result (effects/react-all! system (:events result))]
  (assoc result :effect-errors (:errors effect-result))
  result)
```

## `cond` — multi-way branch

Pairs of `test result`, first truthy test wins. `:else` (a truthy keyword) is the default.

```clojure
;; routes.clj
(cond
  (and (= :get request-method) (= "rooms" root) (nil? id))
  (json-response 200 {:available (booking/available-rooms system)})

  (and (= :post request-method) (= "rooms" root) (= "book" task))
  (->response (booking/book-room! system (form->book-command params id)))

  :else (json-response 404 {:error :no-such-task}))
```

## `case` — branch on a constant value

Faster and clearer than `cond` when you're matching one value against literals.

```clojure
;; fsm.clj
(case (:event/type event)
  :room-booked         (assoc state :status :booked :guest (:guest event))
  :booking-cancelled   (assoc state :status :available :guest nil)
  :room-decommissioned (assoc state :status :decommissioned)
  state)               ; <- trailing form with no test = default
```

The last bare expression is the default. Without one, an unmatched value throws.

## `do` — run several expressions for their effects, return the last

Appears via the compiler in macro output; you'll also see it implicitly (each `when`/`fn`
body is an implicit `do`). Explicit `do` is rare in this code.

---

# 7. Booleans, equality, membership

```clojure
(= a b)              ; value equality (deep, works on maps/vectors)
(not= a b)           ; opposite
(not x)              ; logical not
(and x y z)          ; returns first falsey, or the last value
(or x y)             ; returns first truthy, or the last value
(contains? coll k)   ; is key/element present?
(nil? x)             ; is it nil?
(true? x) (false? x) ; strictly true / false
(boolean x)          ; coerce to true/false
```

Real uses:

```clojure
;; main.clj — `or` as a default
(or (System/getenv "PORT") "3000")
(or s (component/start (system/in-memory-system)))

;; decider.clj — `and` guarding a check
(if (and command-schema (not (m/validate command-schema command)))
  {:error :invalid-command ...}
  ...)

;; contracts.clj — coerce a maybe-nil to a real boolean
(defn valid? [topic payload]
  (boolean (some-> (by-topic topic) (m/validate payload))))

;; announce_booking_test.clj
(is (true? (contracts/valid? (:topic effect) (:payload effect))))
(is (nil? (get-in effect [:payload :guest])))
```

---

# 8. Threading macros

These remove nested-call clutter by passing a value through a pipeline.

## `->` (thread-first)

Inserts the value as the **first** argument of each step.

```clojure
;; postgres.clj
(defn- row->event [row]
  (-> (:events/payload row)        ; start value
      str                          ; (str x)
      (json/parse-string true)     ; (json/parse-string x true)
      (update :event/type keyword)))  ; (update x :event/type keyword)
```

Reads top-to-bottom as "take the payload, stringify, parse JSON, then keyword the type".

## `->>` (thread-last)

Inserts the value as the **last** argument. (Used in this repo mainly inside vendored
hooks; the pattern: `(->> coll (map f) (filter g))`.)

## `some->` — thread, but bail out on the first `nil`

```clojure
;; contracts.clj
(some-> (by-topic topic) (m/validate payload))
;; if (by-topic topic) is nil, stop and return nil; else (m/validate that payload)

;; user.clj
(some-> % component/stop)   ; stop the system only if it isn't nil
```

---

# 9. Working with maps

Maps are immutable: these return a **new** map, they don't mutate.

```clojure
(assoc m :k v)            ; add/replace one key
(assoc-in m [:a :b] v)    ; set a nested key
(update m :k f)           ; replace value at :k with (f old-value)
(update-in m [:a :b] f)   ; same, nested
(get m :k)                ; look up (returns nil if absent)
(get m :k default)        ; look up with fallback
(get-in m [:a :b])        ; nested lookup
(select-keys m [:a :b])   ; sub-map with only those keys
(merge m1 m2)             ; combine, m2 wins on conflicts
```

Real uses:

```clojure
;; fsm.clj
(assoc state :status :booked :guest (:guest event))   ; assoc takes multiple k/v pairs

;; decider.clj
(assoc % :recorded-at (clock/now-interval clock))

;; flows_test.clj
(assoc-in ada-books-102 [:guest :name] "Bob")

;; in_memory.clj
(update-in [:streams stream-id] (fnil into []) events)

;; routes.clj
(get params "guest-name")

;; in_memory.clj
(get-in @db [:streams stream-id] [])    ; nested lookup with [] default

;; effects.clj
(select-keys effect [:guest :room-id :check-in :check-out])

;; process.clj
(merge state {:status :booking-new-room :move-id (:move-id event) ...})
```

`fnil` (above) wraps a function so a `nil` argument becomes a default —
`(fnil into [])` means "if the collection is nil, treat it as `[]`".

---

# 10. Sequence functions

All lazy/immutable; they return new sequences.

```clojure
(map f coll)            ; apply f to each -> seq
(mapv f coll)           ; same but returns a vector
(mapcat f coll)         ; map then concat the results
(filter pred coll)      ; keep where pred is truthy
(remove pred coll)      ; drop where pred is truthy
(keep f coll)           ; map, but drop nil results
(reduce f init coll)    ; fold into one value
(for [x coll] expr)     ; list comprehension
(doseq [x coll] ...)    ; like for, but for side effects, returns nil
(map-indexed f coll)    ; f gets (index item)
(first coll) (rest coll) (count coll)
(into to from)          ; pour one coll into another
(conj coll x)           ; add an item
(vec coll)              ; make a vector
(comp f g)              ; function composition: (comp f g) x = (f (g x))
```

Real uses:

```clojure
;; decider.clj — current-state folds history through evolve
(defn current-state [{:keys [initial-state evolve]} history]
  (reduce evolve initial-state history))

;; decider.clj
(mapv #(assoc % :recorded-at ...) (:events result))

;; events_test.clj
(mapcat :events results)            ; :events used as a function (see note below)

;; projection.clj
(vec (remove #(contains? #{:booked :decommissioned} (view %)) all-room-ids))

;; effects.clj — keep drops nils; for builds the effect list
(vec (keep (comp error-result #(execute! system %))
           (for [event   events
                 reactor reactors
                 effect  (reactor event)]   ; multiple bindings = nested loop
             effect)))

;; postgres.clj — index + item, side-effecting insert
(doseq [[i e] (map-indexed vector events)]
  (jdbc/execute! tx ["insert ..." stream-id (+ expected-version i 1) ...]))

;; postgres.clj
(mapv row->event (jdbc/execute! datasource [...]))
```

Two idioms worth calling out:

- **Keywords are functions of maps.** `(:guest event)` = "get `:guest` from `event`". So
  `(mapcat :events results)` maps the `:events` keyword over each result.
- **`vector` used as a function** in `(map-indexed vector events)` — `vector` just builds
  `[index item]` pairs.

---

# 11. State: atoms and vars

Most of the system is pure. Mutable state is confined to the in-memory test adapters and
the dev REPL helpers.

## `atom`, `swap!`, `@` (deref)

```clojure
;; recording.clj — a mutable box holding a vector
(defrecord RecordingGuestNotifications [sent]   ; sent is an atom
  notify/GuestNotifications
  (confirm-booking! [_ booking] (swap! sent conj booking)))

(defn create [] (->RecordingGuestNotifications (atom [])))
```

- `(atom [])` creates a mutable reference initialised to `[]`.
- `(swap! sent conj booking)` applies `(conj current-value booking)` and stores the result.
- `@sent` (or `(deref sent)`) reads the current value:

```clojure
;; flows_test.clj
@(:sent (:guest-notifications sys))      ; deref the atom inside the system map
```

A more involved `swap!` with a function and an internal consistency check:

```clojure
;; in_memory.clj
(swap! db (fn [s]
            (let [current (get-in s [:streams stream-id] [])]
              (when (not= (count current) expected-version)
                (throw (ex-info "Concurrency conflict" {...})))
              (-> s
                  (update-in [:streams stream-id] (fnil into []) events)
                  (update :all (fnil into []) events)))))
```

## `alter-var-root` — change what a top-level var holds

```clojure
;; user.clj
(defn start! []
  (alter-var-root #'the-system
                  (fn [s] (or s (component/start (system/in-memory-system)))))
  :started)
```

Used for the dev singleton. `#'the-system` is the var box (see §4).

## `delay` + `@` — compute once, lazily

```clojure
;; build.clj
(def basis (delay (b/create-basis {:project "deps.edn"})))
;; ...later...
(b/copy-dir {:src-dirs (:paths @basis) ...})    ; @basis forces it the first time
```

`delay` wraps a computation that runs the first time you deref it, then caches the result.

---

# 12. Errors

## `throw` + `ex-info`

`ex-info` builds an exception carrying a message **and a data map**.

```clojure
;; in_memory.clj
(throw (ex-info "Concurrency conflict"
                {:hotel.event-store/error :concurrency-conflict
                 :stream stream-id
                 :expected-version expected-version
                 :actual-version (count current)}))
```

## `ex-data` — read that data map back

```clojure
;; decider.clj
(defn- concurrency-conflict? [e]
  (= :concurrency-conflict
     (:hotel.event-store/error (ex-data e))))
```

## `try` / `catch`

```clojure
;; decider.clj
(try
  (es/append-events! event-store stream-id (count history) stamped)
  {:events stamped}
  (catch ExceptionInfo e
    (if (concurrency-conflict? e)
      {:error :concurrency-conflict}
      (throw e))))               ; re-throw anything else
```

`(catch ClassName binding body)` — `ClassName` is the Java class to catch (here
`ExceptionInfo`, imported in §1).

A catch with a **type hint** (see §13):

```clojure
;; postgres.clj
(catch SQLException e
  (if (unique-violation? e) (conflict! ...) (throw e)))
```

---

# 13. Java interop

Clojure runs on the JVM and calls Java directly.

## Static method / field: `Class/member`

```clojure
(System/getenv "DATABASE_URL")     ; main.clj
(System/currentTimeMillis)         ; system_clock.clj
(Integer/parseInt (or ... "3000")) ; main.clj
(Runtime/getRuntime)               ; main.clj
```

## Instance method: `(.method obj args)`

```clojure
(.getSQLState e)            ; postgres.clj — calls e.getSQLState()
(.addShutdownHook (Runtime/getRuntime) (Thread. #(component/stop sys)))  ; main.clj
```

## Construct an object: `(ClassName. args)`

The trailing dot means "new".

```clojure
(Thread. #(component/stop sys))   ; main.clj — new Thread(runnable)
```

## Type hints `^Type`

Optional annotations that tell the compiler the Java type, avoiding reflection.

```clojure
;; postgres.clj
(defn- unique-violation? [^SQLException e]   ; e is an SQLException
  (= "23505" (.getSQLState e)))
```

---

# 14. Protocols and records

This is how the codebase defines **ports** (protocols) and **adapters** (records).

## `defprotocol` — declare an interface

```clojure
;; event_store/protocol.clj
(defprotocol EventStore
  (read-stream    [this stream-id]
    "All events for one stream, in order.")
  (append-events! [this stream-id expected-version events]
    "Append with optimistic concurrency: ...")
  (read-all       [this]
    "All events in global order ..."))
```

Each line is a method signature: name, params (first is the instance, `this`), optional
docstring. No bodies — implementations live in records.

## `defrecord` — a data type that can implement protocols

```clojure
;; in_memory.clj
(defrecord InMemoryEventStore [db]            ; db is the single field
  es/EventStore                               ; "this record implements EventStore"
  (read-stream [_ stream-id]
    (get-in @db [:streams stream-id] []))
  (append-events! [_ stream-id expected-version events]
    (swap! db (fn [s] ...)))
  (read-all [_] (:all @db [])))

(defn create [] (->InMemoryEventStore (atom {})))
```

- Fields go in the vector after the name (`[db]`).
- After a protocol name, you list its methods with real bodies.
- `_` is a conventional name for "I'm ignoring this parameter" (here, the instance).

## Implementing two protocols in one record

Production adapters implement both their port **and** Component's `Lifecycle`:

```clojure
;; postgres.clj
(defrecord PostgresEventStore [jdbc-url datasource]
  component/Lifecycle
  (start [this]
    (if datasource this (assoc this :datasource (jdbc/get-datasource {:jdbcUrl jdbc-url}))))
  (stop [this] (assoc this :datasource nil))

  es/EventStore
  (read-stream [_ stream-id] ...)
  (append-events! [_ stream-id expected-version events] ...)
  (read-all [_] ...))
```

## Record constructors: `->Name` and `map->Name`

`defrecord` auto-generates two constructor functions:

```clojure
(->DeterministicClock (atom init))                 ; positional: fields in order
(map->RabbitMqPublisher {:uri uri :exchange exchange})  ; by key
(map->PostgresEventStore {:jdbc-url jdbc-url})
```

---

# 15. Metadata

Extra info attached to a form, written with `^`.

## Docstrings

Already seen — the string right after a `def`/`defn`/`ns` name is stored as metadata.

## `^:keyword` flags

```clojure
;; postgres_test.clj
(deftest ^:integration postgres-store-honours-the-event-store-contract ...)
```

`^:integration` tags this test so the fast suite can **exclude** it (see `deps.edn`'s
`:excludes [:integration]`). `^:foo` is shorthand for `^{:foo true}`.

## `^Type` hints

Covered in §13 — also metadata, attached to a symbol.

---

# 16. The vector-schema DSL (Malli)

Schemas here look like special syntax but are **plain data** (vectors and maps) that the
Malli library interprets. Worth recognising because it's everywhere in the domain.

```clojure
;; events.clj
(def event-schema
  [:multi {:dispatch :event/type}            ; pick a branch by :event/type
   [:room-booked
    [:map                                    ; a map schema
     [:event/type [:= :room-booked]]         ; must EQUAL this keyword
     [:room-id    :string]                   ; predicate schemas: :string :int :keyword
     [:guest      Guest]                     ; reference another schema (a var)
     [:check-in   :string]
     [:recorded-at {:optional true} Interval]]]   ; {:optional true} = key may be absent
   ...])

;; events.clj
(def Guest
  [:map
   [:name  :string]
   [:email [:re #"^[^@\s]+@[^@\s]+$"]]])      ; [:re regex] = must match

;; contracts.clj — {:closed true} = no extra keys allowed
(def room-booked-v1
  [:map {:closed true}
   [:type    [:= "RoomBooked"]]
   [:version [:= 1]]
   [:room-id :string]
   [:check-in :string]])
```

You then *use* the schema through library functions, which are ordinary calls:

```clojure
(m/validate command-schema command)      ; -> true/false
(m/explain  command-schema command)      ; -> data describing failures
(me/humanize (m/explain ...))            ; -> human-readable messages
```

Takeaway: there's no new language here — `[:map ...]` is just a vector whose meaning is
given by Malli.

---

# 17. Testing (`clojure.test`)

## `deftest` and `is`

```clojure
;; cancel_booking_test.clj
(deftest a-booked-room-can-be-cancelled
  (is (= {:events [{:event/type :booking-cancelled :room-id "101"}]}
         (decider/decide cancel/decider
                         [{:event/type :room-booked :room-id "101"}]
                         {:room-id "101"}))))
```

`(is expr)` records a pass if `expr` is truthy. `(is (= expected actual))` is the standard
assertion. You can add a message:

```clojure
;; flows_test.clj
(is (= ["101" "103"] (api/available-rooms sys)) "room is taken")
```

Other predicates seen: `(is (true? x))`, `(is (false? x))`, `(is (nil? x))`,
`(is (empty? coll))`, `(is (map? x))`.

## `testing` — group and label assertions

```clojure
;; event_store/contract.clj
(testing "appended events come back, in order, with their data intact"
  (let [store (fresh-store)]
    (es/append-events! store "room-1" 0 [e1])
    (is (= [e1 e2] (es/read-stream store "room-1")))))
```

## `with-redefs` — temporarily replace functions inside a body

Used to force an error path without real I/O.

```clojure
;; book_room_test.clj
(with-redefs [es/read-stream (fn [_ _] [])
              clock/now-interval (fn [_] {:earliest 1 :latest 1})
              es/append-events! (fn [& _]
                                  (throw (ex-info "Concurrency conflict"
                                                  {:hotel.event-store/error :concurrency-conflict})))]
  (is (= {:error :concurrency-conflict}
         (decider/handle book/decider {:event-store ::store :clock ::clock} "room-101" book-cmd))))
```

Inside the `with-redefs` body those three functions are swapped out; afterwards they
return to normal.

## `run-all-tests` (REPL)

```clojure
;; user.clj
(t/run-all-tests #"booking\..*-test|adapters\..*-test")
```

---

# 18. Quoting and REPL/dev forms

## `'` (quote) — "don't evaluate this, give me the literal"

```clojure
;; build.clj — ns-compile wants the symbol, not its value
:ns-compile '[hotel.rest-api.main]

;; user.clj — refresh :after wants the symbol name
(tn/refresh :after 'user/start!)

;; user.clj — require wants the literal spec
(require '[clojure.test :as t])
```

Without `'`, Clojure would try to *evaluate* `hotel.rest-api.main` or `user/start!` and
either run it or error.

## `comment` — a block that doesn't run

```clojure
;; user.clj
(comment
  (start!)
  (booking/available-rooms the-system)
  ...)
```

Everything inside `(comment ...)` is ignored at load time, but you can still evaluate
individual forms inside it from the editor. This codebase uses it for a REPL walkthrough.

---

# Appendix A — the EDN config files

`deps.edn`, `workspace.edn`, and the `.clj-kondo/.../config.edn` files are **EDN**, not
code. EDN uses the *same literal syntax* as Clojure data — maps `{}`, vectors `[]`,
keywords `:foo`, strings — but it is read as data and never evaluated. Things you'll see:

```clojure
;; deps.edn
{:aliases
 {:dev  {:extra-paths ["development/src"]
         :extra-deps  {org.clojure/clojure {:mvn/version "1.11.3"}
                       hotel/booking {:local/root "components/booking"}}}
  :test {:exec-fn cognitect.test-runner.api/test
         :exec-args {:dirs [...] :excludes [:integration]}}}}
```

- Symbols like `org.clojure/clojure` and `hotel/booking` are just data keys here.
- `:mvn/version`, `:local/root`, `:exec-fn` are namespaced keywords.

No new syntax — just the literals from §3 used as a data file.

---

# Appendix B — constructs only in the vendored clj-kondo / hiccup hooks

These appear **only** in `.clj-kondo/imports/` (third-party hook code shipped with the
`potemkin` and `hiccup` libraries, plus the `hiccup` hook). They are not part of the hotel
application, but they're real Clojure in the repo, so here they are for completeness.

## `defmacro` — define a macro

```clojure
;; potemkin/namespaces.clj
(defmacro import-fn
  ([sym] (import-macro* sym))
  ([sym name] (import-macro* sym name)))
```

A macro receives **unevaluated code** and returns code. The bodies use the templating
syntax below.

## Syntax-quote `` ` ``, unquote `~`, unquote-splice `~@`

```clojure
;; potemkin/namespaces.clj
`(def ~(-> sym name symbol) ~sym)
```

- `` ` `` (backtick) quotes a whole template but lets you punch holes in it.
- `~x` evaluates `x` and drops the value into the template.
- `~@xs` splices a sequence's elements into the surrounding form (seen as `~@(map ... syms)`).

## `#_` and `#_#_` — discard forms

```clojure
;; potemkin/config.edn
:hooks {:macroexpand {#_#_potemkin.namespaces/import-vars potemkin.namespaces/import-vars ...}}
```

`#_` tells the reader to skip the next form. `#_#_` skips the next **two** forms (a quick
way to comment out a key/value pair).

## Core functions that appear only in these hooks

```clojure
(when-not test body)        ; opposite of when
(split-with pred coll)      ; -> [taken dropped]
(concat a b)                ; lazily join seqs
(repeat n x) / (vec (repeat ...))
(reverse coll)
(fnext coll)                ; second element = (first (next coll))
(set coll) / (set/difference a b)
(list* a b more)            ; build a list, last arg is a tail seq
(resolve sym) (meta v)      ; reflection on vars
(name s) (namespace s) (symbol ns name)   ; pull apart / build symbols
(api/list-node ...) (api/vector-node ...) (api/token-node ...)  ; clj-kondo AST builders
```

If you only care about the hotel system, you can ignore this appendix — none of it runs in
the application or its tests.

---

## One-page recap of what's where

| Need | Construct | Section |
|---|---|---|
| Load code | `ns`, `:require :as/:refer`, `:import`, `:gen-class` | §1 |
| Define | `def`, `defn`, `defn-`, multi-arity, `defonce`, `declare` | §2 |
| Data | maps/vectors/sets, keywords, `::`, regex | §3 |
| Functions | `fn`, `#()`, `%` | §4 |
| Locals | `let`, destructuring (`:keys`, `:ns/keys`, `[a & b]`, `:as`) | §5 |
| Branch | `if`, `when`, `when-let`, `if-let`, `cond`, `case` | §6 |
| Logic | `=`, `not=`, `and`, `or`, `contains?`, `nil?` | §7 |
| Pipelines | `->`, `->>`, `some->` | §8 |
| Maps | `assoc(-in)`, `update(-in)`, `get(-in)`, `select-keys`, `merge`, `fnil` | §9 |
| Sequences | `map(v)`, `mapcat`, `filter`, `remove`, `keep`, `reduce`, `for`, `doseq`, `comp` | §10 |
| State | `atom`, `swap!`, `@`, `alter-var-root`, `delay` | §11 |
| Errors | `throw`, `ex-info`, `ex-data`, `try/catch` | §12 |
| Java | `Class/static`, `(.method o)`, `(Class.)`, `^Type` | §13 |
| Ports/adapters | `defprotocol`, `defrecord`, `->Ctor`/`map->Ctor` | §14 |
| Metadata | docstrings, `^:integration`, `^Type` | §15 |
| Schemas | Malli vector DSL | §16 |
| Tests | `deftest`, `is`, `testing`, `with-redefs` | §17 |
| Quoting/REPL | `'`, `comment`, runtime `require` | §18 |
