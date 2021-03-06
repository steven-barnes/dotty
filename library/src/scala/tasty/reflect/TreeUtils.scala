package scala.tasty
package reflect

/** Tasty reflect case definition */
trait TreeUtils
    extends Core
    with SymbolOps
    with TreeOps { self: Reflection =>

  abstract class TreeAccumulator[X] {

    // Ties the knot of the traversal: call `foldOver(x, tree))` to dive in the `tree` node.
    def foldTree(x: X, tree: Tree)(given ctx: Context): X

    def foldTrees(x: X, trees: Iterable[Tree])(given ctx: Context): X = trees.foldLeft(x)(foldTree)

    def foldOverTree(x: X, tree: Tree)(given ctx: Context): X = {
      def localCtx(definition: Definition): Context = definition.symbol.localContext
      tree match {
        case Ident(_) =>
          x
        case Select(qualifier, _) =>
          foldTree(x, qualifier)
        case This(qual) =>
          x
        case Super(qual, _) =>
          foldTree(x, qual)
        case Apply(fun, args) =>
          foldTrees(foldTree(x, fun), args)
        case TypeApply(fun, args) =>
          foldTrees(foldTree(x, fun), args)
        case Literal(const) =>
          x
        case New(tpt) =>
          foldTree(x, tpt)
        case Typed(expr, tpt) =>
          foldTree(foldTree(x, expr), tpt)
        case NamedArg(_, arg) =>
          foldTree(x, arg)
        case Assign(lhs, rhs) =>
          foldTree(foldTree(x, lhs), rhs)
        case Block(stats, expr) =>
          foldTree(foldTrees(x, stats), expr)
        case If(cond, thenp, elsep) =>
          foldTree(foldTree(foldTree(x, cond), thenp), elsep)
        case While(cond, body) =>
          foldTree(foldTree(x, cond), body)
        case Closure(meth, tpt) =>
          foldTree(x, meth)
        case Match(selector, cases) =>
          foldTrees(foldTree(x, selector), cases)
        case Return(expr) =>
          foldTree(x, expr)
        case Try(block, handler, finalizer) =>
          foldTrees(foldTrees(foldTree(x, block), handler), finalizer)
        case Repeated(elems, elemtpt) =>
          foldTrees(foldTree(x, elemtpt), elems)
        case Inlined(call, bindings, expansion) =>
          foldTree(foldTrees(x, bindings), expansion)
        case vdef @ ValDef(_, tpt, rhs) =>
          implicit val ctx = localCtx(vdef)
          foldTrees(foldTree(x, tpt), rhs)
        case ddef @ DefDef(_, tparams, vparamss, tpt, rhs) =>
          implicit val ctx = localCtx(ddef)
          foldTrees(foldTree(vparamss.foldLeft(foldTrees(x, tparams))(foldTrees), tpt), rhs)
        case tdef @ TypeDef(_, rhs) =>
          implicit val ctx = localCtx(tdef)
          foldTree(x, rhs)
        case cdef @ ClassDef(_, constr, parents, derived, self, body) =>
          implicit val ctx = localCtx(cdef)
          foldTrees(foldTrees(foldTrees(foldTrees(foldTree(x, constr), parents), derived), self), body)
        case Import(expr, _) =>
          foldTree(x, expr)
        case clause @ PackageClause(pid, stats) =>
          foldTrees(foldTree(x, pid), stats)(given clause.symbol.localContext)
        case Inferred() => x
        case TypeIdent(_) => x
        case TypeSelect(qualifier, _) => foldTree(x, qualifier)
        case Projection(qualifier, _) => foldTree(x, qualifier)
        case Singleton(ref) => foldTree(x, ref)
        case Refined(tpt, refinements) => foldTrees(foldTree(x, tpt), refinements)
        case Applied(tpt, args) => foldTrees(foldTree(x, tpt), args)
        case ByName(result) => foldTree(x, result)
        case Annotated(arg, annot) => foldTree(foldTree(x, arg), annot)
        case LambdaTypeTree(typedefs, arg) => foldTree(foldTrees(x, typedefs), arg)
        case TypeBind(_, tbt) => foldTree(x, tbt)
        case TypeBlock(typedefs, tpt) => foldTree(foldTrees(x, typedefs), tpt)
        case MatchTypeTree(boundopt, selector, cases) =>
          foldTrees(foldTree(boundopt.fold(x)(foldTree(x, _)), selector), cases)
        case WildcardTypeTree() => x
        case TypeBoundsTree(lo, hi) => foldTree(foldTree(x, lo), hi)
        case CaseDef(pat, guard, body) => foldTree(foldTrees(foldTree(x, pat), guard), body)
        case TypeCaseDef(pat, body) => foldTree(foldTree(x, pat), body)
        case Bind(_, body) => foldTree(x, body)
        case Unapply(fun, implicits, patterns) => foldTrees(foldTrees(foldTree(x, fun), implicits), patterns)
        case Alternatives(patterns) => foldTrees(x, patterns)
      }
    }
  }

  abstract class TreeTraverser extends TreeAccumulator[Unit] {

    def traverseTree(tree: Tree)(given ctx: Context): Unit = traverseTreeChildren(tree)

    def foldTree(x: Unit, tree: Tree)(given ctx: Context): Unit = traverseTree(tree)

    protected def traverseTreeChildren(tree: Tree)(given ctx: Context): Unit = foldOverTree((), tree)

  }

  abstract class TreeMap { self =>

    def transformTree(tree: Tree)(given ctx: Context): Tree = {
      tree match {
        case tree: PackageClause =>
          PackageClause.copy(tree)(transformTerm(tree.pid).asInstanceOf[Ref], transformTrees(tree.stats)(given tree.symbol.localContext))
        case tree: Import =>
          Import.copy(tree)(transformTerm(tree.expr), tree.selectors)
        case tree: Statement =>
          transformStatement(tree)
        case tree: TypeTree => transformTypeTree(tree)
        case tree: TypeBoundsTree => tree // TODO traverse tree
        case tree: WildcardTypeTree => tree // TODO traverse tree
        case tree: CaseDef =>
          transformCaseDef(tree)
        case tree: TypeCaseDef =>
          transformTypeCaseDef(tree)
        case pattern: Bind =>
          Bind.copy(pattern)(pattern.name, pattern.pattern)
        case pattern: Unapply =>
          Unapply.copy(pattern)(transformTerm(pattern.fun), transformSubTrees(pattern.implicits), transformTrees(pattern.patterns))
        case pattern: Alternatives =>
          Alternatives.copy(pattern)(transformTrees(pattern.patterns))
      }
    }

    def transformStatement(tree: Statement)(given ctx: Context): Statement = {
      def localCtx(definition: Definition): Context = definition.symbol.localContext
      tree match {
        case tree: Term =>
          transformTerm(tree)
        case tree: ValDef =>
          implicit val ctx = localCtx(tree)
          val tpt1 = transformTypeTree(tree.tpt)
          val rhs1 = tree.rhs.map(x => transformTerm(x))
          ValDef.copy(tree)(tree.name, tpt1, rhs1)
        case tree: DefDef =>
          implicit val ctx = localCtx(tree)
          DefDef.copy(tree)(tree.name, transformSubTrees(tree.typeParams), tree.paramss mapConserve (transformSubTrees(_)), transformTypeTree(tree.returnTpt), tree.rhs.map(x => transformTerm(x)))
        case tree: TypeDef =>
          implicit val ctx = localCtx(tree)
          TypeDef.copy(tree)(tree.name, transformTree(tree.rhs))
        case tree: ClassDef =>
          ClassDef.copy(tree)(tree.name, tree.constructor, tree.parents, tree.derived, tree.self, tree.body)
        case tree: Import =>
          Import.copy(tree)(transformTerm(tree.expr), tree.selectors)
      }
    }

    def transformTerm(tree: Term)(given ctx: Context): Term = {
      tree match {
        case Ident(name) =>
          tree
        case Select(qualifier, name) =>
          Select.copy(tree)(transformTerm(qualifier), name)
        case This(qual) =>
          tree
        case Super(qual, mix) =>
          Super.copy(tree)(transformTerm(qual), mix)
        case Apply(fun, args) =>
          Apply.copy(tree)(transformTerm(fun), transformTerms(args))
        case TypeApply(fun, args) =>
          TypeApply.copy(tree)(transformTerm(fun), transformTypeTrees(args))
        case Literal(const) =>
          tree
        case New(tpt) =>
          New.copy(tree)(transformTypeTree(tpt))
        case Typed(expr, tpt) =>
          Typed.copy(tree)(transformTerm(expr), transformTypeTree(tpt))
        case tree: NamedArg =>
          NamedArg.copy(tree)(tree.name, transformTerm(tree.value))
        case Assign(lhs, rhs) =>
          Assign.copy(tree)(transformTerm(lhs), transformTerm(rhs))
        case Block(stats, expr) =>
          Block.copy(tree)(transformStats(stats), transformTerm(expr))
        case If(cond, thenp, elsep) =>
          If.copy(tree)(transformTerm(cond), transformTerm(thenp), transformTerm(elsep))
        case Closure(meth, tpt) =>
          Closure.copy(tree)(transformTerm(meth), tpt)
        case Match(selector, cases) =>
          Match.copy(tree)(transformTerm(selector), transformCaseDefs(cases))
        case Return(expr) =>
          Return.copy(tree)(transformTerm(expr))
        case While(cond, body) =>
          While.copy(tree)(transformTerm(cond), transformTerm(body))
        case Try(block, cases, finalizer) =>
          Try.copy(tree)(transformTerm(block), transformCaseDefs(cases), finalizer.map(x => transformTerm(x)))
        case Repeated(elems, elemtpt) =>
          Repeated.copy(tree)(transformTerms(elems), transformTypeTree(elemtpt))
        case Inlined(call, bindings, expansion) =>
          Inlined.copy(tree)(call, transformSubTrees(bindings), transformTerm(expansion)/*()call.symbol.localContext)*/)
      }
    }

    def transformTypeTree(tree: TypeTree)(given ctx: Context): TypeTree = tree match {
      case Inferred() => tree
      case tree: TypeIdent => tree
      case tree: TypeSelect =>
        TypeSelect.copy(tree)(tree.qualifier, tree.name)
      case tree: Projection =>
        Projection.copy(tree)(tree.qualifier, tree.name)
      case tree: Annotated =>
        Annotated.copy(tree)(tree.arg, tree.annotation)
      case tree: Singleton =>
        Singleton.copy(tree)(transformTerm(tree.ref))
      case tree: Refined =>
        Refined.copy(tree)(transformTypeTree(tree.tpt), transformTrees(tree.refinements).asInstanceOf[List[Definition]])
      case tree: Applied =>
        Applied.copy(tree)(transformTypeTree(tree.tpt), transformTrees(tree.args))
      case tree: MatchTypeTree =>
        MatchTypeTree.copy(tree)(tree.bound.map(b => transformTypeTree(b)), transformTypeTree(tree.selector), transformTypeCaseDefs(tree.cases))
      case tree: ByName =>
        ByName.copy(tree)(transformTypeTree(tree.result))
      case tree: LambdaTypeTree =>
        LambdaTypeTree.copy(tree)(transformSubTrees(tree.tparams), transformTree(tree.body))(given tree.symbol.localContext)
      case tree: TypeBind =>
        TypeBind.copy(tree)(tree.name, tree.body)
      case tree: TypeBlock =>
        TypeBlock.copy(tree)(tree.aliases, tree.tpt)
    }

    def transformCaseDef(tree: CaseDef)(given ctx: Context): CaseDef = {
      CaseDef.copy(tree)(transformTree(tree.pattern), tree.guard.map(transformTerm), transformTerm(tree.rhs))
    }

    def transformTypeCaseDef(tree: TypeCaseDef)(given ctx: Context): TypeCaseDef = {
      TypeCaseDef.copy(tree)(transformTypeTree(tree.pattern), transformTypeTree(tree.rhs))
    }

    def transformStats(trees: List[Statement])(given ctx: Context): List[Statement] =
      trees mapConserve (transformStatement(_))

    def transformTrees(trees: List[Tree])(given ctx: Context): List[Tree] =
      trees mapConserve (transformTree(_))

    def transformTerms(trees: List[Term])(given ctx: Context): List[Term] =
      trees mapConserve (transformTerm(_))

    def transformTypeTrees(trees: List[TypeTree])(given ctx: Context): List[TypeTree] =
      trees mapConserve (transformTypeTree(_))

    def transformCaseDefs(trees: List[CaseDef])(given ctx: Context): List[CaseDef] =
      trees mapConserve (transformCaseDef(_))

    def transformTypeCaseDefs(trees: List[TypeCaseDef])(given ctx: Context): List[TypeCaseDef] =
      trees mapConserve (transformTypeCaseDef(_))

    def transformSubTrees[Tr <: Tree](trees: List[Tr])(given ctx: Context): List[Tr] =
      transformTrees(trees).asInstanceOf[List[Tr]]

  }

  /** Bind the `rhs` to a `val` and use it in `body` */
  def let(rhs: Term)(body: Ident => Term): Term = {
    import scala.quoted.QuoteContext
    given QuoteContext = new QuoteContext(this)
    val expr = (rhs.seal: @unchecked) match {
      case '{ $rhsExpr: $t } =>
        '{
          val x = $rhsExpr
          ${
            val id = ('x).unseal.asInstanceOf[Ident]
            body(id).seal
          }
        }
    }
    expr.unseal
  }

  /** Bind the given `terms` to names and use them in the `body` */
  def lets(terms: List[Term])(body: List[Term] => Term): Term = {
    def rec(xs: List[Term], acc: List[Term]): Term = xs match {
      case Nil => body(acc)
      case x :: xs => let(x) { (x: Term) => rec(xs, x :: acc) }
    }
    rec(terms, Nil)
  }
}
