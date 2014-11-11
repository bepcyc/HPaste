package com.gravity.hbase.schema

import java.nio.ByteBuffer
import java.io.IOException
import java.util.Arrays

import scala.collection.mutable.{ ArrayBuffer, Map => MMap }

import org.apache.commons.lang.ArrayUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.util.Bytes.ByteArrayComparator
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.io.ImmutableBytesWritable

import com.gravity.hbase.AnyConverterSignal

/*             )\._.,--....,'``.
 .b--.        /;   _.. \   _\  (`._ ,.
`=,-,-'~~~   `----(,_..'--(,_..'`-.;.'  */

/**
 * Represents the structural configuration for a table
 * @param maxFileSizeInBytes
 */
case class HbaseTableConfig(
                                   maxFileSizeInBytes:Long = -1,
                                   memstoreFlushSizeInBytes:Long = -1,
                                   tablePoolSize:Int = 5
                                   )

object HbaseTable {
  def defaultConfig = HbaseTableConfig()
}

/**
 * Represents a Table.  Expects an instance of HBaseConfiguration to be present.
 * A parameter-type T should be the actual table that is implementing this one
 * (this is to allow syntactic sugar for easily specifying columns during
 * queries).
 * A parameter-type R should be the type of the key for the table.
 * @param tableName
 * @param cache
 * @param rowKeyClass
 * @param logSchemaInconsistencies
 * @param conf
 * @param keyConverter
 * @tparam T
 * @tparam R
 * @tparam RR
 */
abstract class HbaseTable[T <: HbaseTable[T, R, RR], R, RR <: HRow[T, R]](val tableName: String, var cache: QueryResultCache[T, R, RR] = new NoOpCache[T, R, RR](), rowKeyClass: Class[R], logSchemaInconsistencies: Boolean = false, tableConfig:HbaseTableConfig = HbaseTable.defaultConfig)(implicit conf: Configuration, keyConverter: ByteConverter[R]) {


  def rowBuilder(result: DeserializedResult): RR

  def emptyRow(key:Array[Byte]) = rowBuilder(new DeserializedResult(rowKeyConverter.fromBytes(key).asInstanceOf[AnyRef],families.size))
  def emptyRow(key:R) = rowBuilder(new DeserializedResult(key.asInstanceOf[AnyRef],families.size))

  val rowKeyConverter = keyConverter

  /**Provides the client with an instance of the superclass this table was defined against. */
  def pops = this.asInstanceOf[T]

  /**A method injected by the super class that will build a strongly-typed row object.  */
  def buildRow(result: Result): RR = {
    rowBuilder(convertResult(result))
  }

  /**A pool of table objects with AutoFlush set to true */
  val tablePool = new HTablePool(conf, tableConfig.tablePoolSize)

  /**A pool of table objects with AutoFlush set to false --therefore usable for asynchronous write buffering */
  val bufferTablePool = new HTablePool(conf, 1, new HTableInterfaceFactory {
    def createHTableInterface(config: Configuration, tableName: Array[Byte]): HTableInterface = {
      val table = new HTable(conf, tableName)
      table.setWriteBufferSize(2000000L)
      table.setAutoFlush(false)
      table
    }

    def releaseHTableInterface(table: HTableInterface) {
      try {
        table.close()
      } catch {
        case ex: IOException => throw new RuntimeException(ex)
      }
    }
  })


  @volatile var famLookup: Array[Array[Byte]] = null
  @volatile var colFamLookup: Array[Array[Byte]] = null
  @volatile var famIdx: IndexedSeq[KeyValueConvertible[_, _, _]] = null
  @volatile var colFamIdx: IndexedSeq[KeyValueConvertible[_, _, _]] = null

  val bc = new ByteArrayComparator()

  implicit val o = new math.Ordering[Array[Byte]] {
    def compare(a: Array[Byte], b: Array[Byte]): Int = {
      bc.compare(a, b)
    }
  }


  /**Looks up a KeyValueConvertible by the family and column bytes provided.
   * Because of the rules of the system, the lookup goes as follows:
   * 1. Find a column first.  If you find a column first, it means there is a strongly-typed column defined.
   * 2. If no column, then find the family.
   *
   */
  def converterByBytes(famBytes: Array[Byte], colBytes: Array[Byte]): KeyValueConvertible[_, _, _] = {

    if (colFamLookup.length == 0 || famLookup.length == 0) {
      throw new RuntimeException("Attempting to lookup 0 length columns and families--HBaseTable is corrupt")
    }

    val fullKey = ArrayUtils.addAll(famBytes, colBytes)
    val resIdx = Arrays.binarySearch(colFamLookup, fullKey, bc)
    if (resIdx > -1) {
      colFamIdx(resIdx)
    } else {
      val resFamIdx = Arrays.binarySearch(famLookup, famBytes, bc)
      if (resFamIdx > -1) {
        famIdx(resFamIdx)
      }
      else {
        null
      }
    }


  }


  /**Converts a result to a DeserializedObject. A conservative implementation that is slower than convertResultRaw but will always be more stable against
   * binary changes to Hbase's KeyValue format.
   */
  def convertResult(result: Result) = {
    convertResultRaw(result)
  }

  /**
   *
   * @param result
   * @return
   */
  def convertResultRaw(result: Result) = {
    val bytes = new Bytes()
    val cellValues = result.rawCells();




    var ds: DeserializedResult = null
    val rowId = keyConverter.fromBytes(result.getRow).asInstanceOf[AnyRef]
    ds = DeserializedResult(rowId, families.size)

    for (cell <- cellValues) {
      try {
        val f = CellUtil.cloneFamily(cell)
        val r = CellUtil.cloneValue(cell)
        val c = converterByBytes(f,r)
        val q = CellUtil.cloneQualifier(cell)

        val rNew = c.valueConverter.fromBytes(r, 0, r.length).asInstanceOf[AnyRef]
        val qNew = c.keyConverter.fromBytes(q, 0, q.length).asInstanceOf[AnyRef]

        val ts = cell.getTimestamp()
        // println("Adding value " + rNew)
        ds.add(c.family, qNew, rNew, ts)
      } catch {
        case ex: Exception => {
          println("Adding error buffer")
          ex.printStackTrace
        }
      }
    }
    ds
  }


  def familyBytes = families.map(family => family.familyBytes)

  def familyByIndex(idx: Int) = familyArray(idx)

  lazy val familyArray = {
    val arr = new Array[ColumnFamily[_, _, _, _, _]](families.length)
    families.foreach {
      fam =>
        arr(fam.index) = fam
    }
    arr
  }

  def columnByIndex(idx: Int) = columnArray(idx)

  lazy val columnArray = {
    val arr = new Array[Column[_, _, _, _, _]](columns.length)
    columns.foreach {col => arr(col.columnIndex) = col}
    arr
  }


  //alter 'articles', NAME => 'html', VERSIONS =>1, COMPRESSION=>'lzo'

  /**
   * Generates a creation script for the table, based on the column families and table config.
   * @param tableNameOverride
   * @return
   */
  def createScript(tableNameOverride: String = tableName) = {
    var create = "create '" + tableNameOverride + "', "
    create += (for (family <- families) yield {
      familyDef(family)
    }).mkString(",")

    create += alterTableAttributesScripts(tableNameOverride)

    create
  }

  def alterTableAttributesScripts(tableName:String) = {
    var alterScript = ""
    if(tableConfig.memstoreFlushSizeInBytes > -1) {
      alterScript += alterTableAttributeScript(tableName, "MEMSTORE_FLUSHSIZE", tableConfig.memstoreFlushSizeInBytes.toString)
    }
    if(tableConfig.maxFileSizeInBytes > -1) {
      alterScript += alterTableAttributeScript(tableName, "MAX_FILESIZE", tableConfig.maxFileSizeInBytes.toString)
    }
    alterScript
  }

  def alterTableAttributeScript(tableName:String, attributeName:String, value:String) = {
    "\nalter '" + tableName + "', {METHOD => 'table_att', "+attributeName+" => '" + value + "'}"
  }

  def deleteScript(tableNameOverride: String = tableName) = {
    val delete = "disable '" + tableNameOverride + "'\n"

    delete + "delete '" + tableNameOverride + "'"
  }

  /**
   * Generates a production-friendly alter script (flush, disable, alter, enable)
   * @param tableNameOverride
   * @param families
   * @return
   */
  def alterScript(tableNameOverride: String = tableName, families: Seq[ColumnFamily[T, _, _, _, _]] = families) = {

    var alter = "flush '" + tableNameOverride + "'\n"
    alter += "disable '" + tableNameOverride + "'\n"
    alter += "alter '" + tableNameOverride + "', "
    alter += (for (family <- families) yield {
      familyDef(family)
    }).mkString(",")

    alter += alterTableAttributesScripts(tableNameOverride)
    alter += "\nenable '" + tableNameOverride + "'"
    alter
  }

  def familyDef(family: ColumnFamily[T, _, _, _, _]) = {
    val compression = if (family.compressed) ", COMPRESSION=>'lzo'" else ""
    val ttl = if (family.ttlInSeconds < HColumnDescriptor.DEFAULT_TTL) ", TTL=>'" + family.ttlInSeconds + "'" else ""
    "{NAME => '%s', VERSIONS => %d%s%s}".format(Bytes.toString(family.familyBytes), family.versions, compression, ttl)
  }


  def getTable(name: String) = tablePool.getTable(name)

  def getBufferedTable(name: String) = bufferTablePool.getTable(name)

  private val columns = ArrayBuffer[Column[T, R, _, _, _]]()
  val families = ArrayBuffer[ColumnFamily[T, R, _, _, _]]()

  val columnsByName = MMap[AnyRef, Column[T, R, _, _, _]]()

  private val columnsByBytes = MMap[ByteBuffer, KeyValueConvertible[_, _, _]]()
  private val familiesByBytes = MMap[ByteBuffer, KeyValueConvertible[_, _, _]]()

  var columnIdx = 0

  def column[F, K, V](columnFamily: ColumnFamily[T, R, F, K, _], columnName: K, valueClass: Class[V])(implicit fc: ByteConverter[F], kc: ByteConverter[K], kv: ByteConverter[V]) = {
    val c = new Column[T, R, F, K, V](this, columnFamily, columnName, columnIdx)
    columns += c

    val famBytes = columnFamily.familyBytes
    val colBytes = c.columnBytes
    val fullKey = ArrayUtils.addAll(famBytes, colBytes)
    val bufferKey = ByteBuffer.wrap(fullKey)

    columnsByName.put(columnName.asInstanceOf[AnyRef], c)
    columnsByBytes.put(bufferKey, c)
    columnIdx = columnIdx + 1
    c
  }

  var familyIdx = 0

  def family[F, K, V](familyName: F, compressed: Boolean = false, versions: Int = 1, rowTtlInSeconds: Int = Int.MaxValue)(implicit c: ByteConverter[F], d: ByteConverter[K], e: ByteConverter[V]) = {
    val family = new ColumnFamily[T, R, F, K, V](this, familyName, compressed, versions, familyIdx, rowTtlInSeconds)
    familyIdx = familyIdx + 1
    families += family
    familiesByBytes.put(ByteBuffer.wrap(family.familyBytes), family)
    family
  }

  def getTableOption(name: String) = {
    try {
      Some(getTable(name))
    } catch {
      case e: Exception => None
    }
  }


  def withTableOption[Q](name: String)(work: (Option[HTableInterface]) => Q): Q = {
    val table = getTableOption(name)
    try {
      work(table)
    } finally {
      table foreach (tbl => tablePool.putTable(tbl))
    }
  }

  def withBufferedTable[Q](mytableName: String = tableName)(work: (HTableInterface) => Q): Q = {
    val table = getBufferedTable(mytableName)
    try {
      work(table)
    } finally {
      bufferTablePool.putTable(table)
    }
  }

  def withTable[Q](mytableName: String = tableName)(funct: (HTableInterface) => Q): Q = {
    withTableOption(mytableName) {
      case Some(table) => {
        funct(table)
      }
      case None => throw new RuntimeException("Table " + tableName + " does not exist")
    }
  }

  def query2 = new Query2Builder(this)

  def put(key: R, writeToWAL: Boolean = true) = new PutOp(this, keyConverter.toBytes(key))

  def delete(key: R) = new DeleteOp(this, keyConverter.toBytes(key))

  def increment(key: R) = new IncrementOp(this, keyConverter.toBytes(key))

  def init() {
    famLookup = Array.ofDim[Array[Byte]](families.size)
    for ((fam, idx) <- families.zipWithIndex) {
      famLookup(idx) = fam.familyBytes
    }
    Arrays.sort(famLookup, bc)
    famIdx = families.sortBy(_.familyBytes).toIndexedSeq

    colFamLookup = Array.ofDim[Array[Byte]](columns.size)
    for ((col, idx) <- columns.zipWithIndex) {
      colFamLookup(idx) = ArrayUtils.addAll(col.familyBytes, col.columnBytes)
    }
    Arrays.sort(colFamLookup, bc)
    colFamIdx = columns.sortBy(col => ArrayUtils.addAll(col.familyBytes, col.columnBytes)).toIndexedSeq
  }

}