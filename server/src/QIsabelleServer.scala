package server

import scala.concurrent.duration._
import scala.util.matching.Regex

import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.control.IsabelleMLException
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._
import org.checkerframework.checker.units.qual

case class QIsabelleRoutes()(implicit cc: castor.Context, log: cask.Logger) extends cask.Routes {
  // These are the timeouts originally used in PISA.
  var perTransitionTimeout: Duration   = 10.seconds
  var parseAndExecuteTimeout: Duration = Duration.Inf
  val softHammerTimeout: Duration      = 30.seconds
  val midHammerTimeout: Duration       = 35.seconds
  val hardHammerTimeout: Duration      = 40.seconds

  var session: IsabelleSession   = null
  var sledgehammer: Sledgehammer = null

  /** Remembering states by name for the API. */
  var stateMap: Map[String, ToplevelState] = null

  /** A dummy endpoint to just check if the server is running. */
  @cask.get("/")
  def hello() = {
    "Hello from QIsabelle"
  }

  def durationToDouble(d: Duration): Double = {
    if (d == Duration.Inf) 0 else d.div(1.second)
  }

  def doubleToDuration(d: Double): Duration = {
    if (d == 0) Duration.Inf else d.seconds
  }

  /** Start an Isabelle process by loading a given session.
    *
    * @param sessionName
    *   Name of Isabelle session (as defined by a ROOT file) whose heap (a session state cache) we
    *   will load. You can just use 'HOL', but then loading a theory (other than Main) will take a
    *   long time, since it will have to build all its imports.
    * @param sessionRoots
    *   Additional directories in which Isabelle can find sessions (using ROOT and ROOTS files) and
    *   their theories. This should include all the theories you import, regardless of whether they
    *   are in the heap or not. You don't need to give Isabelle source folders like HOL. You may add
    *   "/afp/thys/" as a whole. (These are passed as '-d' options to the 'isabelle build' process.)
    * @param workingDir
    *   Working directory for the Isabelle process. This doesn't influence a lot, mostly how
    *   relative paths are resolved for imports that are not found in the session heap.
    * @param perTransitionTimeoutSeconds
    *   Default timeout for individual transitions (in `execute` and `extractAll`). 0 means no timeout.
    * @param executeTimeoutSeconds
    *   Default timeout for an `execute` call (which can have many transitions). 0 means no timeout.
    *
    * @return
    *   {"success": "success"} or {"error": str, "traceback": str}
    */
  @cask.postJson("/openIsabelleSession")
  def openIsabelleSession(
      sessionName: String,
      sessionRoots: Seq[String],
      workingDir: String,
      perTransitionTimeoutSeconds: Double = durationToDouble(perTransitionTimeout),
      executeTimeoutSeconds: Double = durationToDouble(parseAndExecuteTimeout)
  ): ujson.Obj = {
    println("openIsabelleSession: start")

    perTransitionTimeout = doubleToDuration(perTransitionTimeoutSeconds)
    parseAndExecuteTimeout = doubleToDuration(executeTimeoutSeconds)

    try {
      session = new IsabelleSession(
        isabelleDir = os.Path("/home/isabelle/Isabelle/"),
        sessionName = sessionName,
        sessionRoots = sessionRoots.map(os.Path(_)),
        workingDir = os.Path(workingDir),
        defaultPerTransitionTimeout = perTransitionTimeout,
        defaultParseAndExecuteTimeout = parseAndExecuteTimeout,
        debug = true
      )
      implicit val isabelle = session.isabelle
      sledgehammer = new Sledgehammer(
        softTimeout = softHammerTimeout,
        midTimeout = midHammerTimeout,
        hardTimeout = hardHammerTimeout,
        debug = true
      )
      stateMap = Map()
      println("openIsabelleSession: end")
      return ujson.Obj("success" -> "success")
    } catch {
      case e: Throwable => { closeIsabelleSession(); exceptionJson(e) }
    }
  }

  /** Guess the session name, session roots, and working directory for a given theory file.
    *
    * @param theoryPath
    *   Path to .thy file.
    * @return
    *   {"sessionName": str, "sessionRoots": [str], "workingDir": str}
    *   or {"error": str, "traceback": str}
    */
  @cask.postJson("/guessIsabelleSession")
  def guessIsabelleSession(theoryPath: String): ujson.Obj = {
    try {
      val sessionName  = IsabelleSession.guessSessionName(os.Path(theoryPath))
      val sessionRoots = IsabelleSession.guessSessionRoots(os.Path(theoryPath))
      val workingDir   = os.Path("/home/isabelle/")  // os.Path(theoryPath) / os.up
      return ujson.Obj(
        "sessionName"  -> sessionName,
        "sessionRoots" -> sessionRoots.map(_.toString),
        "workingDir"   -> workingDir.toString
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Stop the Isabelle process and clear the state map and theory from memory.
    *
    * @return
    *   {"success": "Closed"} or {"error": "Already closed", "traceback": ""}
    */
  @cask.postJson("/closeIsabelleSession")
  def closeIsabelleSession(): ujson.Obj = {
    stateMap = null
    sledgehammer = null
    if (session == null)
      return ujson.Obj("error" -> "Already closed", "traceback" -> "")
    session.close()
    session = null
    return ujson.Obj("success" -> "Closed")
  }

  /** Make a new state as if executing `theory Foo imports Bar Baz begin`.
    *
    * @param theoryName
    *   Name of the new theory.
    * @param newStateName
    *   Name of the new state to save the result under.
    * @param imports
    *   List of theory names or paths to import.
    * @param masterDir
    *   Only used to resolve relative path imports that are not found in the session heap. Use
    *   "/home/isabelle/Isabelle/src/", for example.
    * @param onlyImportFromSessionHeap
    *   If true (recommended), only import theories from the session heap (or throw exception).
    * @return
    *   {"success": "success"} or {"error": str, "traceback": str}
    */
  @cask.postJson("/newTheory")
  def newTheory(
      theoryName: String,
      newStateName: String,
      imports: List[String],
      masterDir: String,
      onlyImportFromSessionHeap: Boolean
  ): ujson.Obj = {
    try {
      implicit val isabelle = session.isabelle
      val importedTheories =
        ParsedTheory.loadImports(
          imports,
          session.sessionName,
          debug = true,
          masterDir = os.Path(masterDir),
          onlyFromSessionHeap = onlyImportFromSessionHeap
        )
      val theory = Theory.mergeTheories(theoryName, endTheory = false, theories = importedTheories)
      stateMap += (newStateName -> ToplevelState(theory))
      return ujson.Obj("success" -> "success")
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Load a given theory file until a specified transition.
    *
    * @param theoryPath:
    *   path to .thy file; it is actually loaded and fully parsed (unlike imports, which are loaded
    *   from the heap).
    * @param until:
    *   transition to stop at (Isar outer syntax; whitespace is ignored when comparing). Empty means
    *   execute all the theory until the end (`inclusive` then determines whether the theory is
    *   ended, resulting in a state in Toplevel mode instead of Theory mode).
    * @param inclusive:
    *   whether to include the transition specified by `until` in the result.
    * @param newStateName:
    *   name of the new state to save the result under.
    * @return
    *   Same as `execute()`.
    */
  @cask.postJson("/loadTheory")
  def loadTheory(
      theoryPath: String,
      until: String,
      inclusive: Boolean,
      newStateName: String
  ): ujson.Obj = {
    try {
      implicit val isabelle = session.isabelle
      val parsedTheory = new ParsedTheory(os.Path(theoryPath), session.sessionName, debug = true)
      val newState = {
        if (until.nonEmpty)
          parsedTheory.executeUntil(until, inclusive = inclusive, nDebug = 3)
        else
          parsedTheory.executeAll(stopBeforeEnd = !inclusive, nDebug = 3)
      }
      stateMap += (newStateName -> newState)
      return ujson.Obj(
        "proofGoals" -> newState.proofStateDescription,
        "proofDone"  -> (newState.proofLevel == 0)
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Describe a state, like `State[mode=Proof, localTheory=None, proofState='''\n...\n''']`.
    * @param stateName
    * @return
    *   {"description": str} or {"error": str, "traceback": str}
    *
    * The most common error is "State not found: {stateName}".
    */
  @cask.postJson("/describeState")
  def describeState(stateName: String): ujson.Obj = {
    implicit val isabelle = session.isabelle
    try {
      val state: ToplevelState = getState(stateName)
      return ujson.Obj("description" -> ParsedTheory.describeState(state))
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Parse an execute Isar code on a given state, save the resulting state under a new name.
    *
    * @param stateName
    *   Name of state to execute on.
    * @param isarCode
    *   Isar code to execute (can be multiple transitions, like lemma statements or proofs).
    * @param newStateName
    *   Name of new state to save the result under.
    * @return
    *   - On successful execuction, a JSON object with keys:
    *     - proofGoals: like "proof (prove) goal (1 subgoal): ..." (empty if not in proof mode).
    *     - proofDone: true if there are no more subgoals (proof level is 0, the resulting toplevel
    *       state is not in proof nor skipped-proof mode).
    *   - On error: {"error": str, "traceback": str}
    */
  @cask.postJson("/execute")
  def execute(stateName: String, isarCode: String, newStateName: String): ujson.Obj = {
    implicit val isabelle = session.isabelle
    try {
      val state: ToplevelState = getState(stateName)
      val newState: ToplevelState =
        session.parseAndExecute(isarCode, state, debug = true)
      stateMap += (newStateName -> newState)

      return ujson.Obj(
        "proofGoals" -> newState.proofStateDescription,
        "proofDone"  -> (newState.proofLevel == 0)
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Erase a given state from the state map. */
  @cask.postJson("/forgetState")
  def forgetState(stateName: String): ujson.Obj = {
    stateMap -= stateName
    return ujson.Obj("success" -> "success")
  }

  /** Clear the state map. */
  @cask.postJson("/forgetAllStates")
  def forgetAllStates(stateName: String): ujson.Obj = {
    stateMap = Map()
    return ujson.Obj("success" -> "success")
  }

  /** Run Sledgehammer on a given state.
    *
    * @param stateName
    *   Name of state to run Sledgehammer on (on first subgoal, state must be in proof mode).
    * @param addedFacts
    *   List of fact names to suggest to Sledgehammer.
    * @param deletedFacts
    *   List of fact names to forbid to Sledgehammer.
    * @return
    *   - On success: {"proof": proof}; proof is e.g. "using .. by ..."; the proof is not guaranteed
    *     to be correct.
    *   - On error: {"error": str, "traceback": str}; common errors start with "Sledgehammer
    *     timeout" or "Sledgehammer no proof found".
    */
  @cask.postJson("/hammer")
  def hammer(stateName: String, addedFacts: List[String], deletedFacts: List[String]): ujson.Obj = {
    implicit val isabelle = session.isabelle
    try {
      val state: ToplevelState = getState(stateName)
      return ujson.Obj("proof" -> sledgehammer.findProofOrThrow(state, addedFacts, deletedFacts))
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Extract info about all transitions in a state during an execuction of a given theory file.
    *
    * @param theoryPath
    *    Path to .thy file to be parsed and executed.
    * @return
    *     ```json
    *     {
    *       "theory": {
    *           "path": str,         # Path to this .thy file (in container).
    *           "sessionName": str,  # Name of the Isabelle session that we ran this theory in.
    *           "name": str,         # Name of the theory (from its header).
    *           "imports": [str],    # List of theory names or paths to import (from its header).
    *           "importNames": [str] # List of theory names (resolved).
    *       },
    *       "extractions": [{
    *           "transition": {
    *               "name": str,        # E.g. "theory", "section", "lemma", "by", "using", "definition", 'locale", "record", "end", "<ignored>", "<malformed>".
    *               "text": str,        # Text (code) of the transition.
    *               "position": {
    *                   "line": int,      # Line number of the transition (starts from 1).
    *                   "offset": int,    # Beginning offset of the transition (inclusive).
    *                   "endOffset": int  # End offset of the transition (exclusive).
    *                                     # Offsets count Isabelle symbols (not UTF8 or UTF16) in the whole text (not just the current line).
    *              }
    *           },
    *           "state": {  # State after the transition.
    *               "mode": str,        # One of: Toplevel, Theory (global), LocalTheory, Proof, SkippedProof
    *               "proofState": str,  # Description of the current proof state, starting like "proof (prove)\ngoal (1 subgoal):\n 1. ".
    *               "localTheory": str, # Description like "theory Foo" or "locale foo", or "class foo = ...".
    *               "proofLevel": int   # Number of opened proof blocks, ML function `Toplevel.level`), zero when outside of proofs.
    *                                   # For Skipped_Proof, this is instead the depth (plus one).
    *           }
    *       }, ...]
    *     }
    *     ```
    */

  @cask.postJson("/extractTheory")
  def extractTheory(theoryPath: String): ujson.Obj = {
    try {
      implicit val isabelle = session.isabelle
      val parsedTheory = new ParsedTheory(os.Path(theoryPath), session.sessionName, debug = true)
      return parsedTheory.extractAll(
        perTransitionTimeout = perTransitionTimeout,
        nDebug = 3
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  protected def exceptionJson(e: Throwable): ujson.Obj = {
    e match {
      case q: QIsabelleException => ujson.Obj("error" -> q.message, "traceback" -> "")
      case _ => {
        println(e.toString())
        println("\t\t" + e.getStackTrace().mkString("\n\t\t"))
        ujson.Obj("error" -> e.toString(), "traceback" -> e.getStackTrace().mkString("\n"))
      }
    }
  }

  protected def getState(stateName: String): ToplevelState = {
    stateMap.get(stateName) match {
      case Some(state) => state
      case None        => throw QIsabelleException(s"State not found: $stateName")
    }
  }

  initialize()
}

object QISabelleServer extends cask.Main {
  val allRoutes                   = Seq(QIsabelleRoutes())
  override def debugMode: Boolean = true
  override def host: String       = sys.env.getOrElse("QISABELLE_HOST", "localhost")
  override def port: Int          = sys.env.getOrElse("QISABELLE_PORT", "17000").toInt
  override def main(args: Array[String]): Unit = {
    println("QIsabelleServer starts listening.") // s" on ${host}:${port}")
    super.main(args)
  }
}

final case class QIsabelleException(
    val message: String = "",
    private val cause: Throwable = None.orNull
) extends Exception(message, cause)
