package org.monarchinitiative.dosdp.cli

import java.io.{File, StringReader}

import cats.implicits._
import com.github.tototoshi.csv.{CSVFormat, CSVReader}
import org.backuity.clist._
import org.monarchinitiative.dosdp.{Binding, ExpandedDOSDP, _}
import org.phenoscape.scowl._
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat
import org.semanticweb.owlapi.model.parameters.Imports
import org.semanticweb.owlapi.model.{AxiomType, IRI, OWLAxiom, OWLOntology}

import scala.io.Source
import scala.jdk.CollectionConverters._

object Generate extends Command(description = "generate ontology axioms for TSV input to a Dead Simple OWL Design Pattern") with Common {

  var infile: File = opt[File](name = "infile", default = new File("fillers.tsv"), description = "Input file (TSV or CSV)")
  var restrictAxioms: String = opt[String](name = "restrict-axioms-to", default = "all", description = "Restrict generated axioms to 'logical', 'annotation', or 'all' (default)")
  var restrictAxiomsColumn: Option[String] = opt[Option[String]](name = "restrict-axioms-column", description = "Data column containing local axiom output restrictions")
  var generateDefinedClass: Boolean = opt[Boolean](name = "generate-defined-class", description = "Computed defined class IRI from pattern IRI and variable fillers", default = false)
  var addAxiomSourceAnnotation: Boolean = opt[Boolean](name = "add-axiom-source-annotation", description = "Add axiom annotation to generated axioms linking to pattern IRI", default = false)
  var axiomSourceAnnotationProperty: String = opt[String](name = "axiom-source-annotation-property", description = "IRI for annotation property to use to link generated axioms to pattern IRI", default = "http://www.geneontology.org/formats/oboInOwl#source")

  val LocalLabelProperty: IRI = IRI.create("http://example.org/TSVProvidedLabel")

  def run(): Unit = {
    val (outputLogicalAxioms, outputAnnotationAxioms) = restrictAxioms match {
      case "all"        => (true, true)
      case "logical"    => (true, false)
      case "annotation" => (false, true)
      case other        => throw new UnsupportedOperationException(s"Invalid argument for restrict-axioms-to: $other")
    }
    val sepFormat = tabularFormat
    val patternNames = batchPatterns
    val targets = if (patternNames.nonEmpty) {
      scribe.info("Running in batch mode")
      if (!new File(templateFile).isDirectory) throw new UnsupportedOperationException(s"--template must be a directory in batch mode")
      if (!infile.isDirectory) throw new UnsupportedOperationException(s"--infile must be a directory in batch mode")
      if (!outfile.isDirectory) throw new UnsupportedOperationException(s"--outfile must be a directory in batch mode")
      patternNames.map { pattern =>
        val templateFileName = s"$templateFile/$pattern.yaml"
        val dataExtension = tableFormat.toLowerCase
        val dataFileName = s"$infile/$pattern.$dataExtension"
        val outFileName = s"$outfile/$pattern.ofn"
        GenerateTarget(templateFileName, dataFileName, outFileName)
      }
    } else List(GenerateTarget(templateFile, infile.toString, outfile.toString))
    targets.foreach { target =>
      scribe.info(s"Processing pattern ${target.templateFile}")
      val dosdp = inputDOSDPFrom(target.templateFile)
      val (columns, fillers) = readFillers(new File(target.inputFile), sepFormat)
      val missingColumns = dosdp.allVars.diff(columns)
      missingColumns.foreach(column => scribe.warn(s"Input is missing column for pattern variable <$column>"))
      val outputFile = new File(target.outputFile)
      val axioms: Set[OWLAxiom] = renderPattern(dosdp, prefixes, fillers, ontologyOpt, outputLogicalAxioms, outputAnnotationAxioms, restrictAxiomsColumn, addAxiomSourceAnnotation)
      val manager = OWLManager.createOWLOntologyManager()
      val ont = manager.createOntology(axioms.asJava)
      manager.saveOntology(ont, new FunctionalSyntaxDocumentFormat(), IRI.create(outputFile))
    }
  }

  def readFillers(file: File, sepFormat: CSVFormat): (Set[String], Iterator[Map[String, String]]) = {
    val source = Source.fromFile(file, "utf-8")
    val cleaned = source.getLines().filterNot(_.trim.isEmpty).mkString("\n")
    source.close()
    val iteratorToCheckColumns = CSVReader.open(new StringReader(cleaned))(sepFormat).iteratorWithHeaders
    val columns = if (iteratorToCheckColumns.hasNext) iteratorToCheckColumns.next().keySet else Set.empty[String]
    val reader = new StringReader(cleaned)
    columns -> CSVReader.open(reader)(sepFormat).iteratorWithHeaders
  }

  def renderPattern(dosdp: DOSDP, prefixes: PartialFunction[String, String], fillers: Map[String, String], ontOpt: Option[OWLOntology], outputLogicalAxioms: Boolean, outputAnnotationAxioms: Boolean, restrictAxiomsColumnName: Option[String], annotateAxiomSource: Boolean): Set[OWLAxiom] =
    renderPattern(dosdp, prefixes, Seq(fillers).iterator, ontOpt, outputLogicalAxioms, outputAnnotationAxioms, restrictAxiomsColumnName, annotateAxiomSource)

  def renderPattern(dosdp: DOSDP, prefixes: PartialFunction[String, String], fillers: Iterator[Map[String, String]], ontOpt: Option[OWLOntology], outputLogicalAxioms: Boolean, outputAnnotationAxioms: Boolean, restrictAxiomsColumnName: Option[String], annotateAxiomSource: Boolean): Set[OWLAxiom] = {
    val eDOSDP = ExpandedDOSDP(dosdp, prefixes)
    val readableIDIndex = ontOpt.map(ont => createReadableIdentifierIndex(eDOSDP, ont)).getOrElse(Map.empty)
    val AxiomHasSource = Prefixes.idToIRI(axiomSourceAnnotationProperty, prefixes).map(AnnotationProperty(_))
      .getOrElse(throw new UnsupportedOperationException("Couldn't create IRI for axiom source annotation property."))
    val knownColumns = dosdp.allVars
    val generatedAxioms: Set[OWLAxiom] = (for {
      row <- fillers
    } yield {
      val (varBindingsItems, localLabelItems) = (for {
        vars <- dosdp.vars.toSeq
        varr <- vars.keys
        filler <- row.get(varr).flatMap(stripToOption)
        fillerLabelOpt = for {
          fillerIRI <- Prefixes.idToIRI(filler, prefixes)
          label <- row.get(s"${varr}_label").flatMap(stripToOption)
        } yield fillerIRI -> label
      } yield (varr -> SingleValue(filler.trim), fillerLabelOpt)).unzip
      val varBindings = varBindingsItems.toMap
      val localLabels = LocalLabelProperty -> localLabelItems.flatten.toMap
      val listVarBindings = (for {
        listVars <- dosdp.list_vars.toSeq
        listVar <- listVars.keys
        filler <- row.get(listVar).flatMap(stripToOption)
      } yield listVar -> MultiValue(filler.split(DOSDP.MultiValueDelimiter).map(_.trim).to(Set))).toMap
      val dataVarBindings = (for {
        dataVars <- dosdp.data_vars.toSeq
        dataVar <- dataVars.keys
        filler <- row.get(dataVar).flatMap(stripToOption)
      } yield dataVar -> SingleValue(filler.trim)).toMap
      val dataListBindings = (for {
        dataListVars <- dosdp.data_list_vars.toSeq
        dataListVar <- dataListVars.keys
        filler <- row.get(dataListVar).flatMap(stripToOption)
      } yield dataListVar -> MultiValue(filler.split(DOSDP.MultiValueDelimiter).map(_.trim).to(Set))).toMap
      val additionalBindings = for {
        (key, value) <- row.view.filterKeys(k => !knownColumns(k)).toMap
      } yield key -> SingleValue(value.trim)
      val definedClass = if (generateDefinedClass) {
        dosdp.pattern_iri.flatMap(id => Prefixes.idToIRI(id, prefixes)).map { patternIRI =>
          val bindingsForDefinedClass = varBindings ++ listVarBindings ++ dataVarBindings ++ dataListBindings
          DOSDP.computeDefinedIRI(patternIRI, bindingsForDefinedClass).toString
        }.getOrElse(throw new UnsupportedOperationException("Pattern must have an IRI if generate-defined-class is requested."))
      } else row(DOSDP.DefinedClassVariable).trim
      val iriBinding = DOSDP.DefinedClassVariable -> SingleValue(definedClass)
      val logicalBindings = varBindings + iriBinding
      val readableIDIndexPlusLocalLabels = readableIDIndex + localLabels
      val initialAnnotationBindings = varBindings.view.mapValues(v => irisToLabels(v, eDOSDP, readableIDIndexPlusLocalLabels)).toMap ++
        listVarBindings.view.mapValues(v => irisToLabels(v, eDOSDP, readableIDIndexPlusLocalLabels)).toMap ++
        dataVarBindings ++
        dataListBindings +
        iriBinding
      val annotationBindings = eDOSDP.substitutions.foldLeft(initialAnnotationBindings)((bindings, sub) => sub.expandBindings(bindings)) ++ additionalBindings
      val (localOutputLogicalAxioms, localOutputAnnotationAxioms) = restrictAxiomsColumnName.flatMap(column => row.get(column)).map(_.trim).map {
        case "all"        => (true, true)
        case "logical"    => (true, false)
        case "annotation" => (false, true)
        case ""           => (outputLogicalAxioms, outputAnnotationAxioms)
        case other        => throw new UnsupportedOperationException(s"Invalid value for restrict-axioms-column: $other")
      }.getOrElse((outputLogicalAxioms, outputAnnotationAxioms))
      val logicalAxioms = if (localOutputLogicalAxioms) eDOSDP.filledLogicalAxioms(Some(logicalBindings), Some(annotationBindings)) else Set.empty
      val annotationAxioms = if (localOutputAnnotationAxioms) eDOSDP.filledAnnotationAxioms(Some(annotationBindings), Some(logicalBindings)) else Set.empty
      logicalAxioms ++ annotationAxioms
    }).to(Set).flatten
    if (annotateAxiomSource) {
      val patternIRI = dosdp.pattern_iri.map(IRI.create).getOrElse(throw new UnsupportedOperationException("Axiom annotations require a value for pattern IRI"))
      generatedAxioms.map(_ Annotation(AxiomHasSource, patternIRI))
    } else generatedAxioms
  }

  private def createReadableIdentifierIndex(dosdp: ExpandedDOSDP, ont: OWLOntology): Map[IRI, Map[IRI, String]] = {
    val properties = dosdp.readableIdentifierProperties.to(Set)
    val mappings = for {
      AnnotationAssertion(_, prop, subj: IRI, value ^^ _) <- ont.getAxioms(AxiomType.ANNOTATION_ASSERTION, Imports.INCLUDED).asScala
      if properties(prop)
    } yield Map(prop.getIRI -> Map(subj -> value))
    mappings.fold(Map.empty)(_ combine _)
  }

  private def irisToLabels(binding: Binding, dosdp: ExpandedDOSDP, index: Map[IRI, Map[IRI, String]]): Binding = binding match {
    case SingleValue(value) => SingleValue(Prefixes.idToIRI(value, dosdp.prefixes).map(iri => readableIdentifierForIRI(iri, dosdp, index)).getOrElse(value))
    case MultiValue(values) => MultiValue(values.map(value => Prefixes.idToIRI(value, dosdp.prefixes).map(iri => readableIdentifierForIRI(iri, dosdp, index)).getOrElse(value)))
  }

  private def readableIdentifierForIRI(iri: IRI, dosdp: ExpandedDOSDP, index: Map[IRI, Map[IRI, String]]): String = {
    val properties = dosdp.readableIdentifierProperties.map(_.getIRI) ::: LocalLabelProperty :: Nil
    val labelOpt = properties.collectFirst {
      case prop if index.get(prop).exists(_.isDefinedAt(iri)) => index(prop)(iri)
    }
    labelOpt.getOrElse(iri.toString)
  }

  private def stripToOption(text: String): Option[String] = {
    val trimmed = text.trim
    if (trimmed.isEmpty) None else Some(trimmed)
  }

  private final case class GenerateTarget(templateFile: String, inputFile: String, outputFile: String)

}