(ns build
  "Build the ONE deployable unit:  clojure -T:build uber
   (run from projects/hotel-system). Produces target/hotel-system.jar."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/hotel-system.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   (:paths @basis)
               :target-dir class-dir})
  (b/compile-clj {:basis      @basis
                  :ns-compile '[hotel.rest-api.main]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'hotel.rest-api.main}))
