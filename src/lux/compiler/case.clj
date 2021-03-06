;;   Copyright (c) Eduardo Julian. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lux.compiler.case
  (:require (clojure [set :as set]
                     [template :refer [do-template]])
            [clojure.core.match :as M :refer [match matchv]]
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return fail fail* |let]]
                 [type :as &type]
                 [lexer :as &lexer]
                 [parser :as &parser]
                 [analyser :as &analyser]
                 [host :as &host])
            [lux.compiler.base :as &&])
  (:import (org.objectweb.asm Opcodes
                              Label
                              ClassWriter
                              MethodVisitor)))

;; [Utils]
(let [compare-kv #(.compareTo ^String (aget ^objects %1 0) ^String (aget ^objects %2 0))]
  (defn ^:private compile-match [^MethodVisitor writer ?match $target $else]
    (matchv ::M/objects [?match]
      [["StoreTestAC" ?idx]]
      (doto writer
        (.visitVarInsn Opcodes/ASTORE ?idx)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["BoolTestAC" ?value]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z")
        (.visitLdcInsn ?value)
        (.visitJumpInsn Opcodes/IF_ICMPNE $else)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["IntTestAC" ?value]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Long")
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Long" "longValue" "()J")
        (.visitLdcInsn ?value)
        (.visitInsn Opcodes/LCMP)
        (.visitJumpInsn Opcodes/IFNE $else)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["RealTestAC" ?value]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Double")
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Double" "doubleValue" "()D")
        (.visitLdcInsn ?value)
        (.visitInsn Opcodes/DCMPL)
        (.visitJumpInsn Opcodes/IFNE $else)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["CharTestAC" ?value]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Character")
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Character" "charValue" "()C")
        (.visitLdcInsn ?value)
        (.visitJumpInsn Opcodes/IF_ICMPNE $else)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["TextTestAC" ?value]]
      (doto writer
        (.visitInsn Opcodes/DUP)
        (.visitLdcInsn ?value)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Object" "equals" "(Ljava/lang/Object;)Z")
        (.visitJumpInsn Opcodes/IFEQ $else)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["TupleTestAC" ?members]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
        (-> (doto (.visitInsn Opcodes/DUP)
              (.visitLdcInsn (int idx))
              (.visitInsn Opcodes/AALOAD)
              (compile-match test $next $sub-else)
              (.visitLabel $sub-else)
              (.visitInsn Opcodes/POP)
              (.visitJumpInsn Opcodes/GOTO $else)
              (.visitLabel $next))
            (->> (|let [[idx test] idx+member
                        $next (new Label)
                        $sub-else (new Label)])
                 (doseq [idx+member (->> ?members &/enumerate &/->seq)])))
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))

      [["RecordTestAC" ?slots]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
        (-> (doto (.visitInsn Opcodes/DUP)
              (.visitLdcInsn (int idx))
              (.visitInsn Opcodes/AALOAD)
              (compile-match test $next $sub-else)
              (.visitLabel $sub-else)
              (.visitInsn Opcodes/POP)
              (.visitJumpInsn Opcodes/GOTO $else)
              (.visitLabel $next))
            (->> (|let [[idx [_ test]] idx+member
                        $next (new Label)
                        $sub-else (new Label)])
                 (doseq [idx+member (->> ?slots
                                         &/->seq
                                         (sort compare-kv)
                                         &/->list
                                         &/enumerate
                                         &/->seq)])))
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $target))
      
      [["VariantTestAC" [?tag ?test]]]
      (doto writer
        (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
        (.visitInsn Opcodes/DUP)
        (.visitLdcInsn (int 0))
        (.visitInsn Opcodes/AALOAD)
        (.visitLdcInsn ?tag)
        (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Object" "equals" "(Ljava/lang/Object;)Z")
        (.visitJumpInsn Opcodes/IFEQ $else)
        (.visitInsn Opcodes/DUP)
        (.visitLdcInsn (int 1))
        (.visitInsn Opcodes/AALOAD)
        (-> (doto (compile-match ?test $value-then $value-else)
              (.visitLabel $value-then)
              (.visitInsn Opcodes/POP)
              (.visitJumpInsn Opcodes/GOTO $target)
              (.visitLabel $value-else)
              (.visitInsn Opcodes/POP)
              (.visitJumpInsn Opcodes/GOTO $else))
            (->> (let [$value-then (new Label)
                       $value-else (new Label)]))))
      )))

(defn ^:private separate-bodies [patterns]
  (|let [[_ mappings patterns*] (&/fold (fn [$id+mappings+=matches pattern+body]
                                          (|let [[$id mappings =matches] $id+mappings+=matches
                                                 [pattern body] pattern+body]
                                            (&/T (inc $id) (&/|put $id body mappings) (&/|put $id pattern =matches))))
                                        (&/T 0 (&/|table) (&/|table))
                                        patterns)]
    (&/T mappings (&/|reverse patterns*))))

(defn ^:private compile-pattern-matching [^MethodVisitor writer compile mappings patterns $end]
  (let [entries (&/|map (fn [?branch+?body]
                          (|let [[?branch ?body] ?branch+?body
                                 label (new Label)]
                            (&/T (&/T ?branch label)
                                 (&/T label ?body))))
                        mappings)
        mappings* (&/|map &/|first entries)]
    (doto writer
      (-> (doto (compile-match ?match (&/|get ?body mappings*) $else)
            (.visitLabel $else))
          (->> (|let [[?body ?match] ?body+?match])
               (doseq [?body+?match (&/->seq patterns)
                       :let [$else (new Label)]])))
      (.visitInsn Opcodes/POP)
      (.visitTypeInsn Opcodes/NEW "java/lang/IllegalStateException")
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/IllegalStateException" "<init>" "()V")
      (.visitInsn Opcodes/ATHROW))
    (&/map% (fn [?label+?body]
              (|let [[?label ?body] ?label+?body]
                (|do [:let [_ (.visitLabel writer ?label)]
                      ret (compile ?body)
                      :let [_ (.visitJumpInsn writer Opcodes/GOTO $end)]]
                  (return ret))))
            (&/|map &/|second entries))
    ))

;; [Resources]
(defn compile-case [compile *type* ?value ?matches]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [$end (new Label)]
        _ (compile ?value)
        _ (|let [[mappings patterns] (separate-bodies ?matches)]
            (compile-pattern-matching *writer* compile mappings patterns $end))
        :let [_ (.visitLabel *writer* $end)]]
    (return nil)))
