package com.miriamlaurel.fxcore

import com.miriamlaurel.fxcore.pipscaler._
import scala.math._
import com.miriamlaurel.fxcore.numbers._
import java.io.Serializable
import java.util.{Date, UUID}


/**
 * @author Alexander Temerev
 */
class Position(val primary: Monetary, val secondary: Monetary, matching: UUID = null, timestamp: Date = new Date())
        extends Entity with TimeEvent {

  def this(instrument: Instrument, price: Decimal, amount: Decimal) =
    this(Monetary(amount, instrument.primary), Monetary(-amount * price, instrument.secondary))

  def this(instrument: Instrument, price: Decimal, amount: Decimal, matching: UUID) =
    this(Monetary(amount, instrument.primary), Monetary(-amount * price, instrument.secondary), matching)

  def this(instrument: Instrument, price: Decimal, amount: Decimal, matching: UUID, timestamp: Date) =
    this(Monetary(amount, instrument.primary), Monetary(-amount * price, instrument.secondary), matching, timestamp)

  val matchUuid: Option[UUID] = if (matching != null) Some(matching) else None

  require(primary.amount.signum != secondary.amount.signum)

  lazy val instrument = Instrument(primary.asset, secondary.asset)
  lazy val price: Decimal = (secondary.amount / primary.amount).abs
  lazy val side = if (primary.amount > 0) PositionSide.Long else PositionSide.Short
  lazy val amount: Decimal = primary.amount.abs

  def profitLoss(newPrice: Decimal): Money = {
    Money((newPrice - price) * primary.amount, secondary.asset)
  }

  def profitLoss(q: Quote): Option[Money] =
    for (price <- if (side == PositionSide.Long) q.bid else q.ask) yield profitLoss(price)

  def profitLossIn(asset: Asset, market: Market): Option[Money] = {
    for (q <- market.quote(instrument, amount);
         raw <- profitLoss(q);
         side = if (raw.amount >= 0) OfferSide.Bid else OfferSide.Ask;
         m <- market.convert(raw, asset, side, amount)) yield m
  }

  def close(market: Market): Option[Position] = {
    for (quote <- market.quote(instrument, amount);
         price <- if (side == PositionSide.Long) quote.bid else quote.ask)
    yield new Position(instrument, price, -primary.amount)
  }

  def profitLossPips(price: Decimal): Decimal = instrument match {
    case cp: CurrencyPair => asPips(cp, (price - this.price) * primary.amount.signum)
    case _ => throw new UnsupportedOperationException("Pips operations are defined only on currency positions")
  }

  def profitLossPips(market: Market): Option[Decimal] = for (q <- market.quote(instrument, amount);
                                                             s = PositionSide.close(side);
                                                             p <- q(s)) yield profitLossPips(p)

  def merge(that: Position): (Option[Position], Money) = {

    require(this.instrument == that.instrument)

    // This is highly magical code ported from FI Java implementation once written by me on a sheet of
    // paper and never challenged since then.

    // a, b: initial positions;
    // c: position to collapse;
    // d: remaining position;
    // e: profit/loss position (with zero primary amount)
    // f: resulting position.

    val a1 = this.primary.amount
    val a2 = this.secondary.amount
    val b1 = that.primary.amount
    val b2 = that.secondary.amount
    val c1: Decimal = (if (a1.signum * b1.signum == -1) a1.abs min b1.abs else Decimal(0)) * a1.signum
    val c2: Decimal = if (a1 == 0) a2 else c1 * (a2 / a1)
    val d1: Decimal = -c1
    val d2: Decimal = if (b1 == 0) b2 else d1 * (b2 / b1)
    // e1 is always zero
    val e2: Decimal = c2 + d2
    val sigma = if (a1.abs > b1.abs) -1 else 1
    val f1: Decimal = a1 + b1
    val f2: Decimal = if (a1.signum * b1.signum == 1) a2 + b2 else if (sigma < 0) a2 - c2 else b2 - d2

    val pos: Option[Position] = if (f1 == 0) None else
      Some(new Position(Monetary(f1, primary.asset), Monetary(f2, secondary.asset)))
    return (pos, Money(e2, secondary.asset))
  }

  override def toString = "POSITION " + instrument + " " + primary + " @ " + price
}

object PositionSide extends Enumeration {
  val Long, Short = Value

  def open(side: PositionSide.Value): OfferSide.Value = side match {
    case Long => OfferSide.Ask
    case Short => OfferSide.Bid
  }

  def close(side: PositionSide.Value): OfferSide.Value = side match {
    case Long => OfferSide.Bid
    case Short => OfferSide.Ask
  }

  def reverse(side: PositionSide.Value): PositionSide.Value = side match {
    case Long => Short
    case Short => Long
  }
}

abstract class Portfolio extends Serializable {
  def positions: Iterable[Position]

  def positions(instrument: Instrument): Iterable[Position]

  def <<(position: Position): (Portfolio, PortfolioEvent)

  def amount(instrument: Instrument): Decimal

  def profitLoss(asset: Asset, market: Market): Option[Money]

  def profitLoss(market: Market): Option[Money] = profitLoss(market.pivot, market)
}

class StrictPortfolio protected(val map: Map[Instrument, Position]) extends Portfolio {
  def this() = this (Map())

  lazy val positions = map.values

  def <<(newPosition: Position): (StrictPortfolio, PortfolioEvent) = {
    val oldPosition = map.get(newPosition.instrument)
    val (pos, profitLoss) = oldPosition match {
      case Some(p) => p merge newPosition
      case None => (Some(newPosition), Money(0, newPosition.secondary.asset))
    }
    val (newMap, event) = pos match {
      case Some(position) => (map + (position.instrument -> position), oldPosition match {
        case Some(p) => PositionReplaceEvent(p, position, profitLoss)
        case None => PositionAddEvent(position)
      })
      case None => (map - newPosition.instrument, PositionRemoveEvent(oldPosition.get, profitLoss))
    }
    (new StrictPortfolio(newMap), event)
  }

  def positions(instrument: Instrument): Iterable[Position] = position(instrument) match {
    case Some(pos) => List(pos)
    case None => List()
  }

  def position(instrument: Instrument): Option[Position] = map.get(instrument)

  def amount(instrument: Instrument): Decimal = map.get(instrument) match {
    case None => 0
    case Some(position) => position.primary.amount
  }

  def profitLoss(asset: Asset, market: Market): Option[Money] = {
    // I believe this can be done better
    val plValues = positions.map(_.profitLossIn(asset, market))
    if (plValues.exists(_.isEmpty)) None else
      Some(Money((for (i <- plValues; v <- i) yield v.amount).foldLeft(Decimal(0))(_ + _), asset))
  }

  def size = map.size
}

class NonStrictPortfolio protected(private val aggregates: Map[Instrument, Position],
                                   private val details: Map[Instrument, Map[UUID, Position]])
        extends StrictPortfolio(aggregates) {

  def this() = this (Map(), Map())

  override lazy val positions: Iterable[Position] = details.flatMap(_._2.values)

  override def positions(instrument: Instrument): Iterable[Position] =
    details.getOrElse(instrument, Map[UUID, Position]()).values

  override def <<(newPosition: Position): (NonStrictPortfolio, PortfolioEvent) = {

    val strict = super.<<(newPosition)._1

    def putOrReplacePosition(instrument: Instrument, uuid: UUID, p: Option[Position]): NonStrictPortfolio = {
      val byInstrument = details.getOrElse(instrument, Map[UUID, Position]())
      val newDetails = p match {
        case Some(pos) => details + (instrument -> (byInstrument + (uuid -> pos)))
        case None => details + (instrument -> (byInstrument - uuid))
      }
      new NonStrictPortfolio(strict.map, newDetails)
    }

    newPosition.matchUuid match {
      case Some(uuid) => {
        val oldPosition = for (byInstrument <- details.get(newPosition.instrument);
                               p <- byInstrument.get(uuid)) yield p
        oldPosition match {
          case Some(position) => {
            val result = position merge newPosition
            (putOrReplacePosition(newPosition.instrument, uuid, result._1), result._1 match {
              case Some(merged) => new PositionReplaceEvent(position, merged, result._2)
              case None => new PositionRemoveEvent(position, result._2)
            })
          }
          case None => throw new NoSuchElementException("No matching position found: " + uuid)
        }
      }
      case None => {
        (putOrReplacePosition(newPosition.instrument, newPosition.uuid, Some(newPosition)),
                PositionAddEvent(newPosition))
      }
    }
  }
}

sealed abstract class PortfolioEvent(val position: Position, val profitLoss: Money)

case class PositionAddEvent(pos: Position) extends PortfolioEvent(pos, Zilch)
case class PositionRemoveEvent(pos: Position, pl: Money) extends PortfolioEvent(pos, pl)
case class PositionReplaceEvent(oldPosition: Position, newPosition: Position, pl: Money)
        extends PortfolioEvent(newPosition, pl)

class Account(
        val portfolio: Portfolio,
        val asset: Asset = CurrencyAsset("USD"),
        val balance: Money = Zilch,
        val lastEvent: Option[PortfolioEvent] = None,
        val scale: Int = 2) {

  def <<(position: Position, market: Market): Option[Account] = {
    val (newPortfolio, event) = portfolio << position
    val profitLoss = event.profitLoss
    val closeSide = position.side match {
      case PositionSide.Long => OfferSide.Bid
      case PositionSide.Short => OfferSide.Ask
    }
    for (converted <- market.convert(profitLoss, asset, closeSide, position.amount);
         newBalance = (balance + converted).setScale(scale))
              yield new Account(newPortfolio, asset, newBalance, Some(event), scale)
  }
}