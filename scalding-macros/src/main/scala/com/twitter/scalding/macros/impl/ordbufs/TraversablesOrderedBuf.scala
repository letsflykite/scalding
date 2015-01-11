/*
 Copyright 2014 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.twitter.scalding.macros.impl.ordbufs

import scala.language.experimental.macros
import scala.reflect.macros.Context

import com.twitter.scalding._
import java.nio.ByteBuffer
import com.twitter.scalding.typed.OrderedBufferable

object TraversablesOrderedBuf {
  def dispatch(c: Context)(buildDispatcher: => PartialFunction[c.Type, TreeOrderedBuf[c.type]]): PartialFunction[c.Type, TreeOrderedBuf[c.type]] = {
    case tpe if tpe.erasure =:= c.universe.typeOf[List[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, false)
    case tpe if tpe.erasure =:= c.universe.typeOf[Seq[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, false)
    case tpe if tpe.erasure =:= c.universe.typeOf[Vector[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, false)
    // The erasure of a non-covariant is Set[_], so we need that here for sets
    case tpe if tpe.erasure =:= c.universe.typeOf[Set[Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, true)
    case tpe if tpe.erasure =:= c.universe.typeOf[Map[Any, Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, true)
  }

  def apply(c: Context)(buildDispatcher: => PartialFunction[c.Type, TreeOrderedBuf[c.type]],
    outerType: c.Type,
    doSort: Boolean): TreeOrderedBuf[c.type] = {

    import c.universe._
    def freshT(id: String) = newTermName(c.fresh(s"fresh_$id"))

    val dispatcher = buildDispatcher

    val companionSymbol = outerType.typeSymbol.companionSymbol

    val innerType = if (outerType.asInstanceOf[TypeRefApi].args.size == 2) {
      val (tpe1, tpe2) = (outerType.asInstanceOf[TypeRefApi].args(0), outerType.asInstanceOf[TypeRefApi].args(1))
      val containerType = typeOf[Tuple2[Any, Any]].asInstanceOf[TypeRef]
      TypeRef.apply(containerType.pre, containerType.sym, List(tpe1, tpe2))
    } else {
      outerType.asInstanceOf[TypeRefApi].args.head
    }

    val innerTypes = outerType.asInstanceOf[TypeRefApi].args

    val innerBuf: TreeOrderedBuf[c.type] = dispatcher(innerType)

    def readListSize(bb: TermName) = {
      val initialB = freshT("byteBufferContainer")
      q"""
        val $initialB = $bb.get
        if ($initialB == (-1: Byte)) {
          $bb.getInt
        } else {
          if ($initialB < 0) {
            $initialB.toInt + 256
          } else {
            $initialB.toInt
          }
        }
      """
    }

    def genBinaryCompareFn = {
      val bbA = freshT("bbA")
      val bbB = freshT("bbB")

      val lenA = freshT("lenA")
      val lenB = freshT("lenB")
      val minLen = freshT("minLen")
      val incr = freshT("incr")

      val (innerbbA, innerbbB, innerFunc) = innerBuf.compareBinary
      val curIncr = freshT("curIncr")

      val binaryCompareFn = q"""
        val $lenA = ${readListSize(bbA)}
        val $lenB = ${readListSize(bbB)}

        val $minLen = _root_.scala.math.min($lenA, $lenB)
        var $incr = 0
        val $innerbbA = $bbA
        val $innerbbB = $bbB
        var $curIncr = 0
        while($incr < $minLen && $curIncr == 0) {
          $curIncr = $innerFunc
          $incr = $incr + 1
        }

        if($curIncr != 0) {
          $curIncr
        } else {
          if($lenA < $lenB) {
            -1
          } else if($lenA > $lenB) {
            1
          } else {
            0
          }
        }

      """
      (bbA, bbB, binaryCompareFn)
    }

    def genHashFn = {
      val hashVal = freshT("hashVal")
      // val (innerHashVal, innerHashFn) = innerBuf.hash
      val hashFn = q"""
        $hashVal.hashCode
      """
      (hashVal, hashFn)
    }

    def genGetFn = {
      val (innerGetVal, innerGetFn) = innerBuf.get

      val bb = freshT("bb")
      val len = freshT("len")
      val firstVal = freshT("firstVal")
      val travBuilder = freshT("travBuilder")
      val iter = freshT("iter")
      val getFn = q"""
        val $len = ${readListSize(bb)}
        val $innerGetVal = $bb
        if($len > 0)
        {
          if($len == 1) {
            val $firstVal = $innerGetFn
            $companionSymbol.apply($firstVal) : $outerType
          } else {
            val $travBuilder = $companionSymbol.newBuilder[..$innerTypes]
            var $iter = 0
            while($iter < $len) {
              $travBuilder += $innerGetFn
              $iter = $iter + 1
            }
            $travBuilder.result : $outerType
          }
        } else {
          $companionSymbol.empty : $outerType
        }
      """
      (bb, getFn)
    }

    def genPutFn = {
      val outerBB = freshT("outerBB")
      val outerArg = freshT("outerArg")
      val bytes = freshT("bytes")
      val len = freshT("len")
      val (innerBB, innerInput, innerPutFn) = innerBuf.put
      val (innerInputA, innerInputB, innerCompareFn) = innerBuf.compare

      val lenPut = q"""
         val $len = $outerArg.size
         if ($len < 255) {
          $outerBB.put($len.toByte)
         } else {
          $outerBB.put(-1:Byte)
          $outerBB.putInt($len)
        }"""

      val outerPutFn = if (doSort) {
        q"""
          $lenPut
        val $innerBB = $outerBB

          $outerArg.toArray.sortWith { case (a, b) =>
            val $innerInputA = a
            val $innerInputB = b
            val cmpRes = $innerCompareFn
            cmpRes < 0
          }.foreach{ e =>
          val $innerInput = e
          $innerPutFn
        }
        """
      } else {
        q"""
        $lenPut
        val $innerBB = $outerBB
        $outerArg.foreach { e =>
          val $innerInput = e
          $innerPutFn
        }
        """
      }

      (outerBB, outerArg, outerPutFn)
    }

    def genCompareFn = {
      val inputA = freshT("inputA")
      val inputB = freshT("inputB")
      val (innerInputA, innerInputB, innerCompareFn) = innerBuf.compare

      val lenA = freshT("lenA")
      val lenB = freshT("lenB")
      val aIterator = freshT("aIterator")
      val bIterator = freshT("bIterator")
      val minLen = freshT("minLen")
      val incr = freshT("incr")
      val curIncr = freshT("curIncr")
      val (iterA, iterB) = if (doSort) {
        (q"""
        val $aIterator = $inputA.toArray.sortWith { case (a, b) =>
            val $innerInputA = a
            val $innerInputB = b
            val cmpRes = $innerCompareFn
            cmpRes < 0
          }.toIterator""", q"""
        val $bIterator = $inputB.toArray.sortWith { case (a, b) =>
            val $innerInputA = a
            val $innerInputB = b
            val cmpRes = $innerCompareFn
            cmpRes < 0
          }.toIterator""")
      } else {
        (q"""
          val $aIterator = $inputA.toIterator
          """, q"""
          val $bIterator = $inputB.toIterator
          """)
      }

      val compareFn = q"""
        val $lenA = $inputA.size
        val $lenB = $inputB.size
        $iterA
        $iterB
        val $minLen = _root_.scala.math.min($lenA, $lenB)
        var $incr = 0
        var $curIncr = 0
        while($incr < $minLen && $curIncr == 0 ) {
          val $innerInputA = $aIterator.next
          val $innerInputB = $bIterator.next
          $curIncr = $innerCompareFn
          $incr = $incr + 1
        }

        if($curIncr != 0) {
          $curIncr
        } else {
          if($lenA < $lenB) {
            -1
          } else if($lenA > $lenB) {
            1
          } else {
            0
          }
        }
      """

      (inputA, inputB, compareFn)
    }

    val compareInputA = freshT("compareInputA")
    val compareInputB = freshT("compareInputB")
    val compareFn = q"$compareInputA.compare($compareInputB)"

    new TreeOrderedBuf[c.type] {
      override val ctx: c.type = c
      override val tpe = outerType
      override val compareBinary = genBinaryCompareFn
      override val hash = genHashFn
      override val put = genPutFn
      override val get = genGetFn
      override val compare = genCompareFn
    }
  }
}

