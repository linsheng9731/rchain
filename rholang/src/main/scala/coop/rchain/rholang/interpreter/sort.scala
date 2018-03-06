/**
  * Sorts the insides of the Par and ESet/EMap of the rholangADT
  *
  * A score tree is recursively built for each term and is used to sort the insides of Par/ESet/EMap.
  * For most terms, the current term type's absolute value based on the Score object is added as a Leaf
  * to the left most branch and the score tree built for the inside terms are added to the right.
  * The Score object is a container of constants that arbitrarily assigns absolute values to term types.
  * The sort order is total as every term type is assigned an unique value in the Score object.
  * For ground types, the appropriate integer representation is used as the base score tree.
  * For var types, the Debruijn level from the normalization is used.
  *
  * In order to sort an term, call [Type]SortMatcher.sortMatch(term)
  * and extract the .term  of the returned ScoredTerm.
  */
package coop.rchain.rholang.interpreter

sealed trait Tree[T] {
  def size: Int
}
case class Leaf[T](item: T) extends Tree[T] {
  def size = 1
}
case class Node[T](children: Seq[Tree[T]]) extends Tree[T] {
  def size = children.map(_.size).sum
}

case class ScoreAtom(value: Either[Int, String]) {
  def compare(that: ScoreAtom): Int =
    (this.value, that.value) match {
      case (Left(i1), Left(i2))   => i1.compare(i2)
      case (Left(_), Right(_))    => -1
      case (Right(_), Left(_))    => 1
      case (Right(s1), Right(s2)) => s1.compare(s2)
    }
}

object ScoreAtom {
  def apply(value: Int): ScoreAtom    = new ScoreAtom(Left(value))
  def apply(value: String): ScoreAtom = new ScoreAtom(Right(value))
}

object Leaf {
  def apply(item: Int)    = new Leaf(ScoreAtom(item))
  def apply(item: String) = new Leaf(ScoreAtom(item))
}

object Leaves {
  // Shortcut to be able to write Leaves(1,2,3) instead of Node(Seq(Leaf(1),Leaf(2),Leaf(3)))
  def apply(children: Int*) = new Node(children.map(a => Leaf(ScoreAtom(a))))
}

object Node {
  // Shortcut to write Node(1, Leaf(1)) instead of Node(Seq(Leaf(ScoreAtom(1)), Leaf(ScoreAtom(1))))
  def apply(left: Int, right: Tree[ScoreAtom]*): Tree[ScoreAtom] =
    new Node(Seq(Leaf(left)) ++ right)
}

// Effectively a tuple that groups the term to its score tree.
case class ScoredTerm[T](term: T, score: Tree[ScoreAtom]) extends Ordered[ScoredTerm[T]] {
  def compare(that: ScoredTerm[T]): Int = {
    def compareScore(s1: Tree[ScoreAtom], s2: Tree[ScoreAtom]): Int =
      (s1, s2) match {
        case (Leaf(a), Leaf(b)) => a.compare(b)
        case (Leaf(_), Node(_)) => -1
        case (Node(_), Leaf(_)) => 1
        case (Node(a), Node(b)) =>
          (a, b) match {
            case (Nil, Nil) => 0
            case (Nil, _)   => -1
            case (_, Nil)   => 1
            case (h1 +: t1, h2 +: t2) =>
              compareScore(h1, h2) match {
                case 0     => compareScore(Node(t1), Node(t2))
                case other => other
              }
          }
      }
    compareScore(this.score, that.score)
  }
}

/**
  * Total order of all terms
  *
  * The general order is ground, vars, arithmetic, comparisons, logical, and then others
  */
object Score {
  // Ground types
  final val BOOL    = 1
  final val INT     = 2
  final val STRING  = 3
  final val URI     = 4
  final val PRIVATE = 5
  final val ELIST   = 6
  final val ETUPLE  = 7
  final val ESET    = 8
  final val EMAP    = 9

  // Vars
  final val BOUND_VAR = 50
  final val FREE_VAR  = 51

  // Expr
  final val EVAR   = 100
  final val ENEG   = 101
  final val EMULT  = 102
  final val EDIV   = 103
  final val EPLUS  = 104
  final val EMINUS = 105
  final val ELT    = 106
  final val ELTE   = 107
  final val EGT    = 108
  final val EGTE   = 109
  final val EEQ    = 110
  final val ENEQ   = 111
  final val ENOT   = 112
  final val EAND   = 113
  final val EOR    = 114

  // Other
  final val QUOTE    = 203
  final val CHAN_VAR = 204

  final val SEND    = 300
  final val RECEIVE = 301
  final val EVAL    = 302
  final val NEW     = 303
  final val MATCH   = 304

  final val PAR = 999
}

object BoolSortMatcher {
  def sortMatch(g: GBool): ScoredTerm[GBool] =
    if (g.b) {
      ScoredTerm(g, Leaves(Score.BOOL, 0))
    } else {
      ScoredTerm(g, Leaves(Score.BOOL, 1))
    }
}

object GroundSortMatcher {
  def sortMatch(g: Ground): ScoredTerm[Ground] =
    g match {
      case gb: GBool    => ScoredTerm(g, BoolSortMatcher.sortMatch(gb).score)
      case gi: GInt     => ScoredTerm(g, Leaves(Score.INT, gi.i))
      case gs: GString  => ScoredTerm(g, Node(Score.STRING, Leaf(gs.s)))
      case gu: GUri     => ScoredTerm(g, Node(Score.URI, Leaf(gu.u)))
      case gp: GPrivate => ScoredTerm(gp, Node(Score.PRIVATE, Leaf(gp.p)))
      case gl: EList =>
        val pars = gl.ps.map(par => ParSortMatcher.sortMatch(par))
        ScoredTerm(EList(pars.map(_.term), gl.freeCount), Node(Score.ELIST, pars.map(_.score): _*))
      case gt: ETuple =>
        val pars = gt.ps.map(par => ParSortMatcher.sortMatch(par))
        ScoredTerm(ETuple(pars.map(_.term), gt.freeCount),
                   Node(Score.ETUPLE, pars.map(_.score): _*))
      // Note ESet and EMap rely on the stableness of Scala's sort
      // See https://github.com/scala/scala/blob/2.11.x/src/library/scala/collection/SeqLike.scala#L627
      case gs: ESet =>
        def deduplicate(scoredTerms: List[ScoredTerm[Par]]) =
          scoredTerms.filterNot {
            var set = Set[Par]()
            scoredTerm =>
              {
                val exists = set(scoredTerm.term)
                set += scoredTerm.term
                exists
              }
          }
        val sortedPars       = gs.ps.map(par => ParSortMatcher.sortMatch(par)).sorted
        val deduplicatedPars = deduplicate(sortedPars)
        ScoredTerm(ESet(deduplicatedPars.map(_.term), gs.freeCount),
                   Node(Score.ESET, deduplicatedPars.map(_.score): _*))
      case gm: EMap =>
        def sortKeyValuePair(kv: (Par, Par)): ScoredTerm[Tuple2[Par, Par]] = {
          val (key, value) = kv
          val sortedKey    = ParSortMatcher.sortMatch(key)
          val sortedValue  = ParSortMatcher.sortMatch(value)
          ScoredTerm((sortedKey.term, sortedValue.term), sortedKey.score)
        }
        def deduplicateLastWriteWins(scoredTerms: List[ScoredTerm[Tuple2[Par, Par]]]) =
          scoredTerms.reverse.filterNot {
            var set = Set[Par]()
            scoredTerm =>
              {
                val exists = set(scoredTerm.term._1)
                set += scoredTerm.term._1
                exists
              }
          }.reverse
        val sortedPars       = gm.kvs.map(kv => sortKeyValuePair(kv)).sorted
        val deduplicatedPars = deduplicateLastWriteWins(sortedPars)
        ScoredTerm(EMap(deduplicatedPars.map(_.term), gm.freeCount),
                   Node(Score.EMAP, deduplicatedPars.map(_.score): _*))
    }
}

object ExprSortMatcher {
  def sortBinaryOperation(p1: Par, p2: Par): Tuple2[ScoredTerm[Par], ScoredTerm[Par]] =
    Seq(ParSortMatcher.sortMatch(p1), ParSortMatcher.sortMatch(p2)) match {
      case (p1 +: p2 +: _) => (p1, p2)
      case _               => throw new Error("Unexpected sequence length.")
    }

  def sortMatch(e: Expr): ScoredTerm[Expr] =
    e match {
      case eg: Ground =>
        val sortedGround = GroundSortMatcher.sortMatch(eg)
        ScoredTerm(sortedGround.term, sortedGround.score)
      case en: ENeg =>
        val sortedPar = ParSortMatcher.sortMatch(en.p)
        ScoredTerm(ENeg(sortedPar.term), Node(Score.ENEG, sortedPar.score))
      case ev: EVar =>
        val sortedVar = VarSortMatcher.sortMatch(ev.v)
        ScoredTerm(EVar(sortedVar.term), Node(Score.EVAR, sortedVar.score))
      case em: EMult =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(em.p1, em.p2)
        ScoredTerm(EMult(sortedPar1.term, sortedPar2.term),
                   Node(Score.EMULT, sortedPar1.score, sortedPar2.score))
      case ed: EDiv =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(ed.p1, ed.p2)
        ScoredTerm(EDiv(sortedPar1.term, sortedPar2.term),
                   Node(Score.EDIV, sortedPar1.score, sortedPar2.score))
      case ep: EPlus =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(ep.p1, ep.p2)
        ScoredTerm(EPlus(sortedPar1.term, sortedPar2.term),
                   Node(Score.EPLUS, sortedPar1.score, sortedPar2.score))
      case em: EMinus =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(em.p1, em.p2)
        ScoredTerm(EMinus(sortedPar1.term, sortedPar2.term),
                   Node(Score.EMINUS, sortedPar1.score, sortedPar2.score))
      case el: ELt =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(el.p1, el.p2)
        ScoredTerm(ELt(sortedPar1.term, sortedPar2.term),
                   Node(Score.ELT, sortedPar1.score, sortedPar2.score))
      case el: ELte =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(el.p1, el.p2)
        ScoredTerm(ELte(sortedPar1.term, sortedPar2.term),
                   Node(Score.ELTE, sortedPar1.score, sortedPar2.score))
      case eg: EGt =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(eg.p1, eg.p2)
        ScoredTerm(EGt(sortedPar1.term, sortedPar2.term),
                   Node(Score.EGT, sortedPar1.score, sortedPar2.score))
      case eg: EGte =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(eg.p1, eg.p2)
        ScoredTerm(EGte(sortedPar1.term, sortedPar2.term),
                   Node(Score.EGTE, sortedPar1.score, sortedPar2.score))
      case ee: EEq =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(ee.p1, ee.p2)
        ScoredTerm(EEq(sortedPar1.term, sortedPar2.term),
                   Node(Score.EEQ, sortedPar1.score, sortedPar2.score))
      case en: ENeq =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(en.p1, en.p2)
        ScoredTerm(ENeq(sortedPar1.term, sortedPar2.term),
                   Node(Score.ENEQ, sortedPar1.score, sortedPar2.score))
      case en: ENot =>
        val sortedPar = ParSortMatcher.sortMatch(en.p)
        ScoredTerm(ENot(sortedPar.term), Node(Score.ENOT, sortedPar.score))
      case ea: EAnd =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(ea.p1, ea.p2)
        ScoredTerm(EAnd(sortedPar1.term, sortedPar2.term),
                   Node(Score.EAND, sortedPar1.score, sortedPar2.score))
      case eo: EOr =>
        val (sortedPar1, sortedPar2) = sortBinaryOperation(eo.p1, eo.p2)
        ScoredTerm(EOr(sortedPar1.term, sortedPar2.term),
                   Node(Score.EOR, sortedPar1.score, sortedPar2.score))
    }
}

object VarSortMatcher {
  def sortMatch(v: Var): ScoredTerm[Var] =
    v match {
      case bv: BoundVar => ScoredTerm(bv, Leaves(Score.BOUND_VAR, bv.level))
      case fv: FreeVar  => ScoredTerm(fv, Leaves(Score.FREE_VAR, fv.level))
    }
}

object ChannelSortMatcher {
  def sortMatch(c: Channel): ScoredTerm[Channel] =
    c match {
      case cq: Quote =>
        val sortedPar = ParSortMatcher.sortMatch(cq.p)
        ScoredTerm(Quote(sortedPar.term), Node(Score.QUOTE, sortedPar.score))
      case cv: ChanVar =>
        val sortedVar = VarSortMatcher.sortMatch(cv.cvar)
        ScoredTerm(ChanVar(sortedVar.term), Node(Score.CHAN_VAR, sortedVar.score))
    }
}

object SendSortMatcher {
  def sortMatch(s: Send): ScoredTerm[Send] = {
    val sortedChan = ChannelSortMatcher.sortMatch(s.chan)
    val sortedData = s.data.map(d => ParSortMatcher.sortMatch(d))
    val sortedSend =
      Send(chan = sortedChan.term,
           data = sortedData.map(_.term),
           persistent = s.persistent,
           freeCount = s.freeCount)
    val persistentScore = if (s.persistent) 1 else 0
    val sendScore = Node(
      Score.SEND,
      Seq(Leaf(persistentScore)) ++ Seq(sortedChan.score) ++ sortedData.map(_.score): _*)
    ScoredTerm(sortedSend, sendScore)
  }
}

object ReceiveSortMatcher {
  def sortBind(bind: (List[Channel], Channel)): ScoredTerm[Tuple2[List[Channel], Channel]] = {
    val (patterns, channel) = bind
    val sortedPatterns      = patterns.map(channel => ChannelSortMatcher.sortMatch(channel))
    val sortedChannel       = ChannelSortMatcher.sortMatch(channel)
    ScoredTerm((sortedPatterns.map(_.term), sortedChannel.term),
               Node(Seq(sortedChannel.score) ++ sortedPatterns.map(_.score)))
  }

  // Used during normalize to presort the binds.
  def preSortBinds[T](binds: List[Tuple3[List[Channel], Channel, DebruijnLevelMap[T]]])
    : List[Tuple3[List[Channel], Channel, DebruijnLevelMap[T]]] = {
    val sortedBind = binds.map {
      case (patterns: List[Channel], channel: Channel, knownFree: DebruijnLevelMap[T]) =>
        val sortedBind                      = sortBind((patterns, channel))
        val (sortedPatterns, sortedChannel) = sortedBind.term
        ScoredTerm((sortedPatterns, sortedChannel, knownFree), sortedBind.score)
    }.sorted
    sortedBind.map(_.term)
  }

  // The order of the binds must already be presorted by the time this is called.
  // This function will then sort the insides of the preordered binds.
  def sortMatch(r: Receive): ScoredTerm[Receive] = {
    val sortedBinds     = r.binds.map(bind => sortBind(bind))
    val persistentScore = if (r.persistent) 1 else 0
    val sortedBody      = ParSortMatcher.sortMatch(r.body)
    ScoredTerm(
      Receive(sortedBinds.map(_.term), sortedBody.term, r.persistent, r.bindCount, r.freeCount),
      Node(Score.RECEIVE,
           Seq(Leaf(persistentScore)) ++
             sortedBinds.map(_.score) ++ Seq(sortedBody.score): _*)
    )
  }
}

object EvalSortMatcher {
  def sortMatch(e: Eval): ScoredTerm[Eval] = {
    val sortedChannel = ChannelSortMatcher.sortMatch(e.channel)
    ScoredTerm(Eval(sortedChannel.term), Node(Score.EVAL, sortedChannel.score))
  }
}

object NewSortMatcher {
  def sortMatch(n: New): ScoredTerm[New] = {
    val sortedPar = ParSortMatcher.sortMatch(n.p)
    ScoredTerm(New(bindCount = n.bindCount, p = sortedPar.term),
               Node(Score.NEW, Leaf(n.bindCount), sortedPar.score))
  }
}

object MatchSortMatcher {
  def sortMatch(m: Match): ScoredTerm[Match] = {
    def sortCase(matchCase: Tuple2[Par, Par]): ScoredTerm[Tuple2[Par, Par]] = {
      val (pattern, body) = matchCase
      val sortedPattern   = ParSortMatcher.sortMatch(pattern)
      val sortedBody      = ParSortMatcher.sortMatch(body)
      ScoredTerm((sortedPattern.term, sortedBody.term),
                 Node(Seq(sortedPattern.score) ++ Seq(sortedBody.score)))
    }

    val sortedValue = ParSortMatcher.sortMatch(m.value)
    val scoredCases = m.cases.map(_case => sortCase(_case))
    ScoredTerm(Match(sortedValue.term, scoredCases.map(_.term), m.freeCount),
               Node(Score.MATCH, Seq(sortedValue.score) ++ scoredCases.map(_.score): _*))
  }
}

object ParSortMatcher {
  def sortMatch(p: Par): ScoredTerm[Par] = {
    val sends    = p.sends.map(s => SendSortMatcher.sortMatch(s)).sorted
    val receives = p.receives.map(r => ReceiveSortMatcher.sortMatch(r)).sorted
    val exprs    = p.exprs.map(e => ExprSortMatcher.sortMatch(e)).sorted
    val evals    = p.evals.map(e => EvalSortMatcher.sortMatch(e)).sorted
    val news     = p.news.map(n => NewSortMatcher.sortMatch(n)).sorted
    val matches  = p.matches.map(m => MatchSortMatcher.sortMatch(m)).sorted
    val sortedPar = Par(
      sends = sends.map(_.term),
      receives = receives.map(_.term),
      exprs = exprs.map(_.term),
      evals = evals.map(_.term),
      news = news.map(_.term),
      matches = matches.map(_.term),
      freeCount = p.freeCount
    )
    val parScore = Node(Score.PAR,
                        sends.map(_.score) ++
                          receives.map(_.score) ++ exprs.map(_.score) ++
                          evals.map(_.score) ++ news.map(_.score): _*)
    ScoredTerm(sortedPar, parScore)
  }
}
