/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2009-2010, Jesse Eichar             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scalax.io

import resource._
import scala.collection.Traversable
import java.io.Writer


/**
 * A trait for objects that can have data written to them. For example an
 * OutputStream and File can be an Output object (or be converted to one).
 * <p>
 * Note: Each invocation of a method will typically open a new stream or
 * channel.  That behaviour can be overridden by the implementation but
 * it is the default behaviour.
 * </p>
 *
 * @author Jesse Eichar
 * @since 1.0
 *
 * @see ReadBytes
 * @see Input
 */
trait WriteChars {

    protected def writer : WriteCharsResource[Writer]

  def write(characters : TraversableOnce[Char]) : Unit = {
    for (out <- writer) {
      characters foreach out.append
    }
  }

    /**
    * Writes a string. The open options that can be used are dependent
    * on the implementation and implementors should clearly document
    * which option are permitted.
    *
    * @param string
    *          the data to write
    */
    def writeString(string : String) : Unit = {
      for (out <- writer) {
        out write string
      }
    }

    /**
    * Write several strings. The open options that can be used are dependent
    * on the implementation and implementors should clearly document
    * which option are permitted.
    *
    * @param strings
    *          The data to write
    * @param separator
    *          A string to add between each string.
    *          It is not added to the before the first string
    *          or after the last.
    */
    def writeStrings(strings : Traversable[String], separator : String = "") : Unit = {
        for (out <- writer) {
            (strings foldLeft true) {
                case (true, s) =>
                    out write s
                    false
                case (false, s) =>
                    out write separator
                    out write s
                    false
            }
        }
    }
}