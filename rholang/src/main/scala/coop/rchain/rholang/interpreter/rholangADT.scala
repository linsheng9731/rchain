// Normalization:
// Normalization requires the evaluation of everything that doesn't go through
// the tuplespace, so the top level of a normalized process must have everything

package coop.rchain.rholang.interpreter

import scala.language.implicitConversions

case class Par(
    sends: List[Send],
    receives: List[Receive],
    // selects: List[Select],
    evals: List[Eval],
    news: List[New],
    exprs: List[Expr],
    matches: List[Match],
    freeCount: Int // Makes pattern matching faster.
) {
  def this() =
    this(List(), List(), List(), List(), List(), List(), 0)
  // Single element convenience constructors
  def this(s: Send) =
    this(List(s), List(), List(), List(), List(), List(), s.freeCount)
  def this(r: Receive) =
    this(List(), List(r), List(), List(), List(), List(), r.freeCount)
  def this(e: Eval) =
    this(List(), List(), List(e), List(), List(), List(), e.freeCount)
  def this(n: New) =
    this(List(), List(), List(), List(n), List(), List(), n.freeCount)
  def this(e: Expr) =
    this(List(), List(), List(), List(), List(e), List(), e.freeCount)
  def this(m: Match) =
    this(List(), List(), List(), List(), List(), List(m), m.freeCount)

  // Convenience prepend methods
  def prepend(s: Send): Par =
    this.copy(sends = s :: this.sends, freeCount = this.freeCount + s.freeCount)
  def prepend(r: Receive): Par =
    this.copy(receives = r :: this.receives, freeCount = this.freeCount + r.freeCount)
  def prepend(e: Eval): Par =
    this.copy(evals = e :: this.evals, freeCount = this.freeCount + e.freeCount)
  def prepend(n: New): Par =
    this.copy(news = n :: this.news, freeCount = this.freeCount + n.freeCount)
  def prepend(e: Expr): Par =
    this.copy(exprs = e :: this.exprs, freeCount = this.freeCount + e.freeCount)
  def prepend(m: Match): Par =
    this.copy(matches = m :: this.matches, freeCount = this.freeCount + m.freeCount)

  def singleEval(): Option[Eval] =
    if (sends.isEmpty && receives.isEmpty && news.isEmpty && exprs.isEmpty) {
      evals match {
        case List(single) => Some(single)
        case _            => None
      }
    } else {
      None
    }

  def singleNew(): Option[New] =
    if (sends.isEmpty && receives.isEmpty && evals.isEmpty && exprs.isEmpty) {
      news match {
        case List(single) => Some(single)
        case _            => None
      }
    } else {
      None
    }

  def merge(that: Par) =
    Par(that.sends ++ sends,
        that.receives ++ receives,
        that.evals ++ evals,
        that.news ++ news,
        that.exprs ++ exprs,
        that.matches ++ matches,
        that.freeCount + freeCount)
}

object Par {
  def apply(): Par           = new Par()
  def apply(s: Send): Par    = new Par(s)
  def apply(r: Receive): Par = new Par(r)
  def apply(e: Eval): Par    = new Par(e)
  def apply(n: New): Par     = new Par(n)
  def apply(e: Expr): Par    = new Par(e)
  def apply(m: Match): Par   = new Par(m)

  implicit def fromSend(s: Send): Par       = apply(s)
  implicit def fromReceive(r: Receive): Par = apply(r)
  implicit def fromEval(e: Eval): Par       = apply(e)
  implicit def fromNew(n: New): Par         = apply(n)
  implicit def fromExpr(e: Expr): Par       = apply(e)
  implicit def fromMatch(m: Match): Par     = apply(m)
}

sealed trait Channel {
  def freeCount: Int
}
case class Quote(p: Par) extends Channel {
  def freeCount: Int = p.freeCount
}
case class ChanVar(cvar: Var) extends Channel {
  def freeCount: Int = cvar.freeCount
}

// While we use vars in both positions, when producing the normalized
// representation we need a discipline to track whether a var is a name or a
// process.
// These are DeBruijn levels
sealed trait Var {
  def freeCount: Int
}
case class BoundVar(level: Int) extends Var {
  def freeCount: Int = 0
}
// Wildcards are represented as bound variables. The initial normalization will
// not produce uses of the variable, but for (_ <- x) P is the same as
// for (y <- x) P if y is not free in P. We model that equivalence by turning all
// wildcards into bound variables.
// Variables that occur free in Par used as a pattern or ChanVar are binders.
// For the purpose of comparing patterns, we count just like BoundVars.

// In the DeBruijn level paper, they use negatives, but this is more clear.
case class FreeVar(level: Int) extends Var {
  def freeCount: Int = 1
}

// Upon send, all free variables in data are substituted with their values.
// also if a process is sent, it is auto-quoted.
case class Send(chan: Channel, data: List[Par], persistent: Boolean, freeCount: Int)

// [Par] is an n-arity Pattern.
// It's an error for free Variable to occur more than once in a pattern.
// Don't currently support conditional receive
// Count is the number of free variables in the formals
case class Receive(binds: List[(List[Channel], Channel)],
                   body: Par,
                   persistent: Boolean,
                   bindCount: Int,
                   freeCount: Int)

case class Eval(channel: Channel) {
  def freeCount: Int = channel.freeCount
}

// Number of variables bound in the new statement.
// For normalized form, p should not contain solely another new.
// Also for normalized form, the first use should be level+0, next use level+1
// up to level+count for the last used variable.
case class New(bindCount: Int, p: Par) {
  def freeCount: Int = p.freeCount
}

case class Match(value: Par, cases: List[(Par, Par)], freeCount: Int)

// Any process may be an operand to an expression.
// Only processes equivalent to a ground process of compatible type will reduce.
sealed trait Expr {
  def freeCount: Int
}
sealed trait Ground                                    extends Expr
case class EList(ps: List[Par], freeCount: Int)        extends Ground
case class ETuple(ps: List[Par], freeCount: Int)       extends Ground
case class ESet(ps: List[Par], freeCount: Int)         extends Ground
case class EMap(kvs: List[(Par, Par)], freeCount: Int) extends Ground
// A variable used as a var should be bound in a process context, not a name
// context. For example:
// for (@x <- c1; @y <- c2) { z!(x + y) } is fine, but
// for (x <- c1; y <- c2) { z!(x + y) } should raise an error.
case class EVar(v: Var) extends Expr {
  def freeCount: Int = v.freeCount
}
case class ENot(p: Par) extends Expr {
  def freeCount: Int = p.freeCount
}
case class ENeg(p: Par) extends Expr {
  def freeCount: Int = p.freeCount
}
case class EMult(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EDiv(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EPlus(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EMinus(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class ELt(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class ELte(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EGt(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EGte(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EEq(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class ENeq(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EAnd(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}
case class EOr(p1: Par, p2: Par) extends Expr {
  def freeCount: Int = p1.freeCount + p2.freeCount
}

case class GBool(b: Boolean) extends Ground {
  def freeCount: Int = 0
}
case class GInt(i: Integer) extends Ground {
  def freeCount: Int = 0
}
case class GString(s: String) extends Ground {
  def freeCount: Int = 0
}
case class GUri(u: String) extends Ground {
  def freeCount: Int = 0
}
// These should only occur as the program is being evaluated. There is no way in
// the grammar to construct them.
case class GPrivate(p: String) extends Ground {
  def freeCount: Int = 0
}
