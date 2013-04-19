package scalax.file.ramfs

import scala.actors.Actor

import java.io.IOException
import language.reflectiveCalls
import java.nio.file.{ FileSystem, PathMatcher, Path, FileStore, WatchService, Files, NoSuchFileException }
import java.nio.file.attribute.{UserPrincipalLookupService, FileAttribute}
import java.nio.file.spi.FileSystemProvider
import java.lang.{ Iterable => JIterable }
import java.util.{ UUID, Set => JSet }
import java.net.{URI, URLDecoder, URLEncoder}
import scalax.file.ImplicitConverters._
import scalax.file.PathMatcher.{ StandardSyntax, RegexPathMatcher, GlobPathMatcher }
import collection.JavaConverters._
import java.nio.file.AccessMode
import java.util.regex.Pattern

protected[ramfs] sealed trait RamFsMsg
object RamFsMsg {
	protected[ramfs] case object Stop extends RamFsMsg
	protected[ramfs] case object IsRunning extends RamFsMsg
	protected[ramfs] case class CreateFile(path: RamPath, createParents: Boolean, attr: Map[RamAttributes.RamAttribute,Object]) extends RamFsMsg 
	protected[ramfs] case class CreateDir(path: RamPath, createParents: Boolean, attr: Map[RamAttributes.RamAttribute,Object]) extends RamFsMsg 
	protected[ramfs] case class Lookup(path: RamPath) extends RamFsMsg 
}

protected[ramfs] sealed trait RamFsResponse
object RamFsResponse {
	protected[ramfs] case object Running extends RamFsResponse
	protected[ramfs] case object Stopped extends RamFsResponse
	protected[ramfs] case class Lookup(node: Node) extends RamFsResponse
}

protected[ramfs] class RamFsActor(fileSystem: RamFileSystem) extends Actor {
  private[this] val sep = fileSystem.separator
  private[this] var fsTree = new DirNode(sep)

  private[this] var running = true
  def act() {
    while (running) {
      receive {
        case msg: RamFsMsg =>
          receiveCommand(msg).foreach(resp => receiver ! resp)
        case msg =>
          System.err.println(this + " recieved an illegal message, should only receive RamFsMsg objects.  Instead got: " + msg)
          System.exit(1);
          false
      }
    }
  }

  def receiveCommand(msg: RamFsMsg): Option[RamFsResponse] = msg match {
    case RamFsMsg.Stop =>
      running = false
      None
  }
  
  private def lookup(path: RamPath) = {
    val absolutePath = path.toAbsolutePath.segments
    fsTree.lookup(absolutePath)
  }

  private def create(path: RamPath, fac: NodeFac, createParents: Boolean, attrs:Map[RamAttributes.RamAttribute,Object]): Node = {
    if (path == fileSystem.root) {
      fsTree
    } else {
      val absolute = path.toAbsolutePath
      Option(absolute.getParent) match {
        case Some(p) if !p.exists && !createParents =>
          throw new java.io.FileNotFoundException("Parent directory " + p + " does not exist")
        case _ => ()
      }

      val x = fsTree.create(absolute.segments.drop(1), fac)
      x.attributes ++= attrs
      x
    }
  }
  private def delete(path: RamPath, force: Boolean): Boolean = {
    if (path.exists) {
      def delete(p: Path) = force || (Files.isWritable(p) && Option(p.getParent).forall { Files.isWritable })

      if (delete(path) && path != fileSystem.root) {
        val parentPath = Option(path.toAbsolutePath.getParent)
        val deletions = for {
          parent <- Option(path.toAbsolutePath.getParent)
          parentNode <- lookup(parent)
          node <- lookup(path)
        } yield {
          parentNode.asInstanceOf[DirNode].children -= node
          true
        }
        deletions.isDefined
      } else if (path == fileSystem.root) {
        fsTree = new DirNode(sep)
        true
      } else {
        false
      }
    } else {
      false
    }
  }

  private def move(src: RamPath, dest: RamPath) = {
    if (src == fileSystem.root) {
      throw new java.io.IOException("Root cannot be moved")
    }
    val parentNode =
      Option(dest.getParent) match {
        case Some(fileSystem.root) | None =>
          fsTree
        case Some(parent) =>
          create(parent, DirNode, true, Map.empty) // TODO paramaterize NodeFactory
          lookup(parent).get.asInstanceOf[DirNode]
      }

    lookup(src) foreach { node =>
      node.name = dest.name
      parentNode.children += node
    }

    delete(src, true)
  }

  /**
   * creates and copies the data of the src node to the destination.
   * Assumption is the destination does not exist
   */
  private def copyFile(src: RamPath, dest: RamPath) = {
    val srcNode = {
      val node = lookup(src) getOrElse (throw new NoSuchFileException(src+" does not exist"))
      if(!node.isInstanceOf[FileNode]) throw new IOException("Path does not reference a file")
      node.asInstanceOf[FileNode]
    }
    dest.getFileSystem.actor ! RamFsMsg.CreateFile(dest, true, Map.empty)
    val newNode = lookup(dest).collect {
      case newNode: FileNode =>
        newNode.data.clear
        newNode.data ++= srcNode.data
    }
  }
}