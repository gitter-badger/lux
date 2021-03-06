;;   Copyright (c) Eduardo Julian. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lux.analyser.lambda
  (:require [clojure.core.match :as M :refer [matchv]]
            clojure.core.match.array
            (lux [base :as & :refer [|let |do return fail]]
                 [host :as &host])
            (lux.analyser [base :as &&]
                          [env :as &env])))

;; [Resource]
(defn with-lambda [self self-type arg arg-type body]
  (|let [[?module1 ?name1] self
         [?module2 ?name2] arg]
    (&/with-closure
      (|do [scope-name &/get-scope-name]
        (&env/with-local (str ?module1 ";" ?name1) self-type
          (&env/with-local (str ?module2 ";" ?name2) arg-type
            (|do [=return body
                  =captured &env/captured-vars]
              (return (&/T scope-name =captured =return)))))))))

(defn close-over [scope ident register frame]
  (matchv ::M/objects [register]
    [[_ register-type]]
    (|let [register* (&/T (&/V "captured" (&/T scope
                                               (->> frame (&/get$ &/$CLOSURE) (&/get$ &/$COUNTER))
                                               register))
                          register-type)
           [?module ?name] ident
           full-name (str ?module ";" ?name)]
      (&/T register* (&/update$ &/$CLOSURE #(->> %
                                                 (&/update$ &/$COUNTER inc)
                                                 (&/update$ &/$MAPPINGS (fn [mps] (&/|put full-name register* mps))))
                                frame)))))
