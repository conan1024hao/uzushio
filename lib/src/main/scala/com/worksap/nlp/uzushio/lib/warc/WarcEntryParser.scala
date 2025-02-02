package com.worksap.nlp.uzushio.lib.warc

import com.worksap.nlp.uzushio.lib.html.{AllTagMapper, ParagraphExtractor}
import com.worksap.nlp.uzushio.lib.lang.{
  EstimationFailure,
  LangEstimation,
  LangTagSniffer,
  ProbableLanguage
}
import com.worksap.nlp.uzushio.lib.warc.WarcEntryParser.resolveEarliestDate
import org.apache.hc.core5.http.impl.nio.{
  DefaultHttpResponseFactory,
  DefaultHttpResponseParser,
  SessionBufferAccess
}
import org.apache.hc.core5.http.{HttpException, HttpMessage, MessageHeaders}
import org.apache.tika.detect.EncodingDetector
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.html.{HtmlMapper, HtmlParser}
import org.apache.tika.sax.BodyContentHandler
import org.mozilla.universalchardet.UniversalDetector

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.nio.charset.{
  Charset,
  IllegalCharsetNameException,
  StandardCharsets,
  UnsupportedCharsetException
}
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import java.util.{Locale, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.xml.SAXException

case class CrawlContent(
    docId: String,
    url: String,
    language: String,
    charset: String,
    text: String,
    date: String
)
class WarcEntryParser {

  private val charsetDetector = new UniversalDetector()
  private val sniffer = new LangTagSniffer()
  private val responseParser = new DefaultHttpResponseParser(
    new DefaultHttpResponseFactory()
  )
  private val sessionInputBuffer = SessionBufferAccess.instance(4 * 1024, 1024)
  private val langEstimation = new LangEstimation()

  private def detectCharsetFromBytes(
      data: Array[Byte],
      offset: Int,
      length: Int
  ): String = {
    val detector = charsetDetector
    detector.reset()
    detector.handleData(data, offset, length)
    detector.dataEnd()
    detector.getDetectedCharset
  }

  def parseHttpHeader(bytes: Array[Byte]): Option[(HttpMessage, Int)] = {
    sessionInputBuffer.clear()
    sessionInputBuffer.putBytes(bytes)
    try {
      responseParser.reset()
      val resp = responseParser.parse(sessionInputBuffer, true)
      if (resp == null) {
        return None
      }
      if (resp.getCode != 200) {
        return None
      }
      Some((resp, sessionInputBuffer.position()))
    } catch {
      case _: HttpException => None
      case _: IOException   => None
      case _: IllegalArgumentException => None
    }
  }

  private def lookupCharset(name: String): Option[Charset] = {
    try {
      Some(Charset.forName(name))
    } catch {
      case _: IllegalCharsetNameException => None
      case _: UnsupportedCharsetException => None
    }
  }

  private def guessCharsetFromMetaTags(
      content: Array[Byte],
      offset: Int
  ): Option[Charset] = {
    val sniff = sniffer.sniffTags(content, offset, content.length - offset)
    lookupNormalizedCharset(sniff.charset)
  }

  private val win31j = Charset.forName("windows-31j")

  private def lookupNormalizedCharset(name: String) = {
    val charsetName = name.toLowerCase(Locale.ROOT)
    charsetName match {
      case ""               => None
      case "utf-8" | "utf8" => Some(StandardCharsets.UTF_8)
      case "shiftjis" | "shift_jis" | "shift-jis" => Some(win31j)
      case x                                      => lookupCharset(x)
    }
  }

  private def guessCharsetFromHeader(
      headers: MessageHeaders
  ): Option[Charset] = {
    val contentTypeHeader = headers.getFirstHeader("Content-Type")
    if (contentTypeHeader == null) {
      return None
    }
    val contentType = contentTypeHeader.getValue
    if (contentType == null) {
      return None
    }
    val cs = LangTagSniffer.extractCharset(contentType)
    lookupNormalizedCharset(cs)
  }

  private def guessCharsetFromBytes(
      content: Array[Byte],
      offset: Int
  ): Charset = {
    val charset =
      detectCharsetFromBytes(content, offset, content.length - offset)
    if (charset == null) {
      return StandardCharsets.UTF_8
    }
    try {
      Charset.forName(charset)
    } catch {
      case _: UnsupportedCharsetException => StandardCharsets.UTF_8
      case _: IllegalCharsetNameException => StandardCharsets.UTF_8
    }
  }

  // noinspection DuplicatedCode
  private def guessCharsetAndLanguage(
      headers: MessageHeaders,
      data: Array[Byte],
      offset: Int
  ): Option[(Charset, String)] = {
    val c1 = guessCharsetFromMetaTags(data, offset)
    if (c1.isDefined) {
      langEstimation.estimateLang(data, offset, c1.get) match {
        case ProbableLanguage(lang) => return Some((c1.get, lang))
        case EstimationFailure      => return None
        case _                      => // do nothing
      }
    }
    val c2 = guessCharsetFromHeader(headers)
    if (c2.isDefined) {
      langEstimation.estimateLang(data, offset, c2.get) match {
        case ProbableLanguage(lang) => return Some((c2.get, lang))
        case EstimationFailure      => return None
        case _                      => // do nothing
      }
    }
    val c3 = guessCharsetFromBytes(data, offset)
    langEstimation.estimateLang(data, offset, c3) match {
      case ProbableLanguage(lang) => Some((c3, lang))
      case _                      => None
    }
  }

  private val parser = new HtmlParser()
  private val metadata = new Metadata()

  /** Extracts paragraphs from HTML document
    * @param data
    *   bytes of html file
    * @param bodyOffset
    *   offset from the start of data array
    * @param cs
    *   document charset
    * @return
    *   array of paragraphs in the HTML document
    */
  def parseHtml(
      data: Array[Byte],
      bodyOffset: Int,
      cs: Charset
  ): Seq[String] = {
    val result = new ArrayBuffer[String]()
    val handler = new BodyContentHandler(
      new ParagraphExtractor(result)
    )
    val inputStream =
      new ByteArrayInputStream(data, bodyOffset, data.length - bodyOffset)
    val parseContext = new ParseContext()
    parseContext.set(
      classOf[EncodingDetector],
      new EncodingDetector {
        override def detect(input: InputStream, metadata: Metadata): Charset =
          cs
      }
    )
    parseContext.set(classOf[HtmlMapper], new AllTagMapper)

    try {
      parser.parse(inputStream, handler, metadata, parseContext)
    } catch {
      case _: SAXException                    => // ignore
      case _: TikaException                   => // ignore
      case _: StringIndexOutOfBoundsException => // ignore
      case _: NullPointerException            => // ignore
    }
    result
  }

  def convert(item: WarcRecord): Option[CrawlContent] = {
    parseHttpHeader(item.content).flatMap { case (header, bodyOffset) =>
      guessCharsetAndLanguage(header, item.content, bodyOffset).map {
        case (cs, lang) =>
          CrawlContent(
            docId = WarcEntryParser.parseWarcUuid(item.docId),
            url = item.url,
            text = parseHtml(item.content, bodyOffset, cs).mkString("\n\n"),
            language = lang,
            charset = cs.name(),
            date = resolveEarliestDate(item.accessDate, header)
          )
      }
    }
  }
}

object WarcEntryParser {
  private val UTC = ZoneId.of("UTC")
  private val httpDateFormat = DateTimeFormatter.RFC_1123_DATE_TIME
  private val isoDateFormat = DateTimeFormatter.ISO_DATE_TIME.withZone(UTC)

  def resolveEarliestDate(value: String, message: HttpMessage): String = {
    val d1 = parseDate(None, isoDateFormat, value)
    val d2 = parseDate(d1, httpDateFormat, message.headerValue("Date"))
    val d3 = parseDate(d2, httpDateFormat, message.headerValue("Last-Modified"))
    d3.map(date => isoDateFormat.format(date)).getOrElse("")
  }

  implicit class MessageExt(val m: HttpMessage) extends AnyVal {
    def headerValue(name: String): String = {
      val h = m.getFirstHeader(name)
      if (h == null) {
        return null
      }
      h.getValue
    }
  }

  private val dateCutoff = LocalDateTime.of(1999, 1, 1, 0, 0, 0)

  def parseWarcUuid(strUuid: String): String = {
    // <urn:uuid:f1a9564a-ae00-40ef-838e-a4486a83fd1d>
    if (
      strUuid.startsWith("<urn:uuid:") && strUuid.endsWith(
        ">"
      ) && strUuid.length == 47
    ) {
      strUuid.substring(10, 46)
    } else {
      UUID.randomUUID().toString
    }
  }

  def parseDate(
      base: Option[LocalDateTime],
      parser: DateTimeFormatter,
      value: String
  ): Option[LocalDateTime] = {
    if (value == null) {
      return base
    }
    try {
      val parsed = ZonedDateTime
        .from(parser.parse(value))
        .withZoneSameInstant(UTC)
        .toLocalDateTime
      if (parsed.isBefore(dateCutoff)) {
        return base
      }
      base match {
        case None => Some(parsed)
        case Some(x) =>
          if (x.isAfter(parsed)) {
            Some(parsed)
          } else {
            Some(x)
          }
      }
    } catch {
      case _: DateTimeParseException => base
    }
  }
}
