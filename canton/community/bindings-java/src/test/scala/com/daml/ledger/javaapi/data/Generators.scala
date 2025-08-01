// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data

import com.daml.ledger.api.*
import com.daml.ledger.api.v2.{
  CommandsOuterClass,
  StateServiceOuterClass,
  TransactionFilterOuterClass,
}
import com.google.protobuf.{ByteString, Empty, Timestamp}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

import java.time.{Duration, Instant, LocalDate}
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

object Generators {

  def valueGen: Gen[v2.ValueOuterClass.Value] =
    Gen.sized(height =>
      if (height <= 0) unitValueGen
      else
        Gen.oneOf(
          recordValueGen,
          variantValueGen,
          contractIdValueGen,
          listValueGen,
          int64ValueGen,
          decimalValueGen,
          textValueGen,
          timestampValueGen,
          partyValueGen,
          boolValueGen,
          unitValueGen,
          dateValueGen,
        )
    )

  def recordGen: Gen[v2.ValueOuterClass.Record] =
    for {
      recordId <- Gen.option(identifierGen)
      fields <- Gen.sized(height =>
        for {
          size <- Gen.size.flatMap(maxSize => Gen.chooseNum(1, math.max(maxSize, 1)))
          newHeight = height / size
          withLabel <- Arbitrary.arbBool.arbitrary
          recordFields <- Gen.listOfN(size, Gen.resize(newHeight, recordFieldGen(withLabel)))
        } yield recordFields
      )
    } yield {
      val builder = v2.ValueOuterClass.Record.newBuilder()
      recordId.foreach(builder.setRecordId)
      builder.addAllFields(fields.asJava)
      builder.build()
    }

  def recordValueGen: Gen[v2.ValueOuterClass.Value] = recordGen.map(valueFromRecord)

  def valueFromRecord(
      record: v2.ValueOuterClass.Record
  ): com.daml.ledger.api.v2.ValueOuterClass.Value =
    v2.ValueOuterClass.Value.newBuilder().setRecord(record).build()

  def identifierGen: Gen[v2.ValueOuterClass.Identifier] =
    for {
      moduleName <- Gen.nonEmptyListOf(Gen.alphaChar).map(_.mkString)
      entityName <- Gen.nonEmptyListOf(Gen.alphaChar).map(_.mkString)
      packageId <- Gen.nonEmptyListOf(Gen.alphaChar).map(_.mkString)
    } yield v2.ValueOuterClass.Identifier
      .newBuilder()
      .setModuleName(moduleName)
      .setEntityName(entityName)
      .setPackageId(packageId)
      .build()

  def recordLabelGen: Gen[String] =
    for {
      head <- Arbitrary.arbChar.arbitrary
      tail <- Arbitrary.arbString.arbitrary
    } yield head +: tail

  def recordFieldGen(withLabel: Boolean): Gen[v2.ValueOuterClass.RecordField] =
    if (withLabel) {
      for {
        label <- recordLabelGen
        value <- valueGen
      } yield v2.ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value).build()
    } else {
      valueGen.flatMap(v2.ValueOuterClass.RecordField.newBuilder().setValue(_).build())
    }

  def unitValueGen: Gen[v2.ValueOuterClass.Value] =
    Gen.const(v2.ValueOuterClass.Value.newBuilder().setUnit(Empty.newBuilder().build()).build())

  def variantGen: Gen[v2.ValueOuterClass.Variant] =
    for {
      variantId <- identifierGen
      constructor <- Arbitrary.arbString.arbitrary
      value <- valueGen
    } yield v2.ValueOuterClass.Variant
      .newBuilder()
      .setVariantId(variantId)
      .setConstructor(constructor)
      .setValue(value)
      .build()

  def variantValueGen: Gen[v2.ValueOuterClass.Value] =
    variantGen.map(v2.ValueOuterClass.Value.newBuilder().setVariant(_).build())

  def optionalGen: Gen[v2.ValueOuterClass.Optional] =
    Gen
      .option(valueGen)
      .map(_.fold(v2.ValueOuterClass.Optional.getDefaultInstance) { v =>
        v2.ValueOuterClass.Optional.newBuilder().setValue(v).build()
      })

  def optionalValueGen: Gen[v2.ValueOuterClass.Value] =
    optionalGen.map(v2.ValueOuterClass.Value.newBuilder().setOptional(_).build())

  def contractIdValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbString.arbitrary.map(
      v2.ValueOuterClass.Value.newBuilder().setContractId(_).build()
    )

  def byteStringGen: Gen[ByteString] =
    Arbitrary.arbString.arbitrary.map(str => com.google.protobuf.ByteString.copyFromUtf8(str))

  def listGen: Gen[v2.ValueOuterClass.List] =
    Gen
      .sized(height =>
        for {
          size <- Gen.size
            .flatMap(maxSize => if (maxSize >= 1) Gen.chooseNum(1, maxSize) else Gen.const(1))
          newHeight = height / size
          list <- Gen
            .listOfN(size, Gen.resize(newHeight, valueGen))
            .map(_.asJava)
        } yield list
      )
      .map(v2.ValueOuterClass.List.newBuilder().addAllElements(_).build())

  def listValueGen: Gen[v2.ValueOuterClass.Value] =
    listGen.map(v2.ValueOuterClass.Value.newBuilder().setList(_).build())

  def textMapGen: Gen[v2.ValueOuterClass.TextMap] =
    Gen
      .sized(height =>
        for {
          size <- Gen.size
            .flatMap(maxSize => if (maxSize >= 1) Gen.chooseNum(1, maxSize) else Gen.const(1))
          newHeight = height / size
          keys <- Gen.listOfN(size, Arbitrary.arbString.arbitrary)
          values <- Gen.listOfN(size, Gen.resize(newHeight, valueGen))
        } yield (keys zip values).map { case (k, v) =>
          v2.ValueOuterClass.TextMap.Entry.newBuilder().setKey(k).setValue(v).build()
        }
      )
      .map(x => v2.ValueOuterClass.TextMap.newBuilder().addAllEntries(x.asJava).build())

  def textMapValueGen: Gen[v2.ValueOuterClass.Value] =
    textMapGen.map(v2.ValueOuterClass.Value.newBuilder().setTextMap(_).build())

  def genMapGen: Gen[v2.ValueOuterClass.GenMap] =
    Gen
      .sized(height =>
        for {
          size <- Gen.size
            .flatMap(maxSize => if (maxSize >= 1) Gen.chooseNum(1, maxSize) else Gen.const(1))
          newHeight = height / size
          keys <- Gen.listOfN(size, Gen.resize(newHeight, valueGen))
          values <- Gen.listOfN(size, Gen.resize(newHeight, valueGen))
        } yield (keys zip values).map { case (k, v) =>
          v2.ValueOuterClass.GenMap.Entry.newBuilder().setKey(k).setValue(v).build()
        }
      )
      .map(x => v2.ValueOuterClass.GenMap.newBuilder().addAllEntries(x.asJava).build())

  def genMapValueGen: Gen[v2.ValueOuterClass.Value] =
    genMapGen.map(v2.ValueOuterClass.Value.newBuilder().setGenMap(_).build())

  def int64ValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbLong.arbitrary.map(v2.ValueOuterClass.Value.newBuilder().setInt64(_).build())

  def textValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbString.arbitrary.map(v2.ValueOuterClass.Value.newBuilder().setText(_).build())

  def timestampValueGen: Gen[v2.ValueOuterClass.Value] =
    instantGen.map(instant =>
      v2.ValueOuterClass.Value.newBuilder().setTimestamp(instant.toEpochMilli * 1000).build()
    )

  def protoTimestampGen: Gen[Timestamp] =
    instantGen.map(instant =>
      Timestamp.newBuilder().setNanos(instant.getNano).setSeconds(instant.getEpochSecond).build()
    )

  def instantGen: Gen[Instant] =
    Gen
      .chooseNum(
        Instant.parse("0001-01-01T00:00:00Z").toEpochMilli,
        Instant.parse("9999-12-31T23:59:59.999999Z").toEpochMilli,
      )
      .map(Instant.ofEpochMilli)

  def partyValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbString.arbitrary.map(v2.ValueOuterClass.Value.newBuilder().setParty(_).build())

  def boolValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbBool.arbitrary.map(v2.ValueOuterClass.Value.newBuilder().setBool(_).build())

  def dateValueGen: Gen[v2.ValueOuterClass.Value] =
    localDateGen.map(d => v2.ValueOuterClass.Value.newBuilder().setDate(d.toEpochDay.toInt).build())

  def localDateGen: Gen[LocalDate] =
    Gen
      .chooseNum(LocalDate.parse("0001-01-01").toEpochDay, LocalDate.parse("9999-12-31").toEpochDay)
      .map(LocalDate.ofEpochDay)

  def decimalValueGen: Gen[v2.ValueOuterClass.Value] =
    Arbitrary.arbBigDecimal.arbitrary.map(d =>
      v2.ValueOuterClass.Value.newBuilder().setNumeric(d.bigDecimal.toPlainString).build()
    )

  def eventGen: Gen[v2.EventOuterClass.Event] = {
    import v2.EventOuterClass.Event
    for {
      event <- Gen.oneOf(
        createdEventGen.map(e => (b: Event.Builder) => b.setCreated(e)),
        archivedEventGen.map(e => (b: Event.Builder) => b.setArchived(e)),
      )
    } yield v2.EventOuterClass.Event
      .newBuilder()
      .pipe(event)
      .build()
  }

  def treeEventGen: Gen[v2.TransactionOuterClass.TreeEvent] = {
    import v2.TransactionOuterClass.TreeEvent
    for {
      event <- Gen.oneOf(
        createdEventGen.map(e => (b: TreeEvent.Builder) => b.setCreated(e)),
        exercisedEventGen.map(e => (b: TreeEvent.Builder) => b.setExercised(e)),
      )
    } yield v2.TransactionOuterClass.TreeEvent
      .newBuilder()
      .pipe(event)
      .build()
  }

  def topologyEventGen: Gen[v2.TopologyTransactionOuterClass.TopologyEvent] = {
    import v2.TopologyTransactionOuterClass.TopologyEvent
    for {
      event <- Gen.oneOf(
        participantAuthorizationChangedGen.map(e =>
          (b: TopologyEvent.Builder) => b.setParticipantAuthorizationChanged(e)
        ),
        participantAuthorizationAddedGen.map(e =>
          (b: TopologyEvent.Builder) => b.setParticipantAuthorizationAdded(e)
        ),
        participantAuthorizationRevokedGen.map(e =>
          (b: TopologyEvent.Builder) => b.setParticipantAuthorizationRevoked(e)
        ),
      )
    } yield v2.TopologyTransactionOuterClass.TopologyEvent
      .newBuilder()
      .pipe(event)
      .build()
  }

  private[this] val failingStatusGen = Gen const com.google.rpc.Status.getDefaultInstance

  private[this] val interfaceViewGen: Gen[v2.EventOuterClass.InterfaceView] =
    Gen.zip(identifierGen, Gen.either(recordGen, failingStatusGen)).map { case (id, vs) =>
      val b = v2.EventOuterClass.InterfaceView.newBuilder().setInterfaceId(id)
      vs.fold(b.setViewValue, b.setViewStatus).build()
    }

  val packageNameGen: Gen[String] = Arbitrary.arbString.arbitrary.suchThat(_.nonEmpty)

  val packageVersionGen: Gen[String] = for {
    major <- Gen.choose(0, 100)
    minor <- Gen.choose(0, 100)
    patch <- Gen.choose(0, 100)
  } yield new PackageVersion(Array(major, minor, patch)).toString

  val packageVettingRequirementsGen
      : Gen[v2.interactive.InteractiveSubmissionServiceOuterClass.PackageVettingRequirement] =
    for {
      packageName <- packageNameGen
      parties <- Gen.listOf(Arbitrary.arbString.arbitrary)
    } yield v2.interactive.InteractiveSubmissionServiceOuterClass.PackageVettingRequirement
      .newBuilder()
      .addAllParties(parties.asJavaCollection)
      .setPackageName(packageName)
      .build()

  val packageReferenceGen: Gen[v2.PackageReferenceOuterClass.PackageReference] =
    for {
      packageId <- Arbitrary.arbString.arbitrary
      packageVersion <- packageVersionGen
      packageName <- packageNameGen
    } yield {
      v2.PackageReferenceOuterClass.PackageReference
        .newBuilder()
        .setPackageId(packageId)
        .setPackageName(packageName)
        .setPackageVersion(packageVersion)
        .build()
    }

  val getPreferredPackagesResponseGen: Gen[
    v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackagesResponse
  ] =
    for {
      packageReferences <- Gen.listOf(packageReferenceGen)
      synchronizerId <- Arbitrary.arbString.arbitrary
    } yield {
      v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackagesResponse
        .newBuilder()
        .addAllPackageReferences(packageReferences.asJavaCollection)
        .setSynchronizerId(synchronizerId)
        .build()
    }

  val packagePreferenceGen: Gen[
    v2.interactive.InteractiveSubmissionServiceOuterClass.PackagePreference
  ] =
    for {
      packageId <- Arbitrary.arbString.arbitrary
      packageVersion <- packageVersionGen
      packageName <- packageNameGen
      synchronizerId <- Arbitrary.arbString.arbitrary
    } yield {
      v2.interactive.InteractiveSubmissionServiceOuterClass.PackagePreference
        .newBuilder()
        .setPackageReference(
          v2.PackageReferenceOuterClass.PackageReference
            .newBuilder()
            .setPackageId(packageId)
            .setPackageName(packageName)
            .setPackageVersion(packageVersion)
            .build()
        )
        .setSynchronizerId(synchronizerId)
        .build()
    }

  def createdEventGen(nodeId: Integer): Gen[v2.EventOuterClass.CreatedEvent] =
    for {
      contractId <- contractIdValueGen.map(_.getContractId)
      templateId <- identifierGen
      packageName <- packageNameGen
      createdAt <- protoTimestampGen
      createArgument <- recordGen
      createEventBlob <- byteStringGen
      interfaceViews <- Gen.listOf(interfaceViewGen)
      offset <- Arbitrary.arbLong.arbitrary
      witnessParties <- Gen.listOf(Arbitrary.arbString.arbitrary)
      signatories <- Gen.listOf(Gen.asciiPrintableStr)
      observers <- Gen.listOf(Gen.asciiPrintableStr)
    } yield v2.EventOuterClass.CreatedEvent
      .newBuilder()
      .setCreatedAt(createdAt)
      .setContractId(contractId)
      .setTemplateId(templateId)
      .setPackageName(packageName)
      .setCreateArguments(createArgument)
      .setCreatedEventBlob(createEventBlob)
      .addAllInterfaceViews(interfaceViews.asJava)
      .setOffset(offset)
      .setNodeId(nodeId)
      .addAllWitnessParties(witnessParties.asJava)
      .addAllSignatories(signatories.asJava)
      .addAllObservers(observers.asJava)
      .build()

  val createdEventGen: Gen[v2.EventOuterClass.CreatedEvent] =
    for {
      nodeId <- Arbitrary.arbInt.arbitrary
      createdEvent <- createdEventGen(nodeId)
    } yield createdEvent

  val archivedEventGen: Gen[v2.EventOuterClass.ArchivedEvent] =
    for {
      contractId <- contractIdValueGen.map(_.getContractId)
      templateId <- identifierGen
      offset <- Arbitrary.arbLong.arbitrary
      nodeId <- Arbitrary.arbInt.arbitrary
      witnessParties <- Gen.listOf(Arbitrary.arbString.arbitrary)

    } yield v2.EventOuterClass.ArchivedEvent
      .newBuilder()
      .setContractId(contractId)
      .setTemplateId(templateId)
      .setOffset(offset)
      .setNodeId(nodeId)
      .addAllWitnessParties(witnessParties.asJava)
      .build()

  val exercisedEventGen: Gen[v2.EventOuterClass.ExercisedEvent] =
    for {
      nodeId <- Arbitrary.arbInt.arbitrary
      lastDescendantNodeId <- Arbitrary.arbInt.arbitrary
      exercisedEvent <- exercisedEventGen(nodeId, lastDescendantNodeId)
    } yield exercisedEvent

  def exercisedEventGen(
      nodeId: Integer,
      lastDescendantNodeId: Integer,
  ): Gen[v2.EventOuterClass.ExercisedEvent] =
    for {
      contractId <- contractIdValueGen.map(_.getContractId)
      templateId <- identifierGen
      actingParties <- Gen.listOf(Arbitrary.arbString.arbitrary)
      offset <- Arbitrary.arbLong.arbitrary
      choice <- Arbitrary.arbString.arbitrary
      choiceArgument <- valueGen
      isConsuming <- Arbitrary.arbBool.arbitrary
      witnessParties <- Gen.listOf(Arbitrary.arbString.arbitrary)
      exerciseResult <- valueGen
    } yield v2.EventOuterClass.ExercisedEvent
      .newBuilder()
      .setContractId(contractId)
      .setTemplateId(templateId)
      .addAllActingParties(actingParties.asJava)
      .setChoice(choice)
      .setChoiceArgument(choiceArgument)
      .setConsuming(isConsuming)
      .setOffset(offset)
      .setNodeId(nodeId)
      .setLastDescendantNodeId(lastDescendantNodeId)
      .addAllWitnessParties(witnessParties.asJava)
      .setExerciseResult(exerciseResult)
      .build()

  val participantPermissionGen: Gen[StateServiceOuterClass.ParticipantPermission] =
    Gen.oneOf(
      v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_SUBMISSION,
      v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_CONFIRMATION,
      v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_OBSERVATION,
    )

  val participantAuthorizationChangedGen
      : Gen[v2.TopologyTransactionOuterClass.ParticipantAuthorizationChanged] =
    for {
      partyId <- Arbitrary.arbString.arbitrary
      participantId <- Arbitrary.arbString.arbitrary
      permission <- participantPermissionGen
    } yield v2.TopologyTransactionOuterClass.ParticipantAuthorizationChanged
      .newBuilder()
      .setPartyId(partyId)
      .setParticipantId(participantId)
      .setParticipantPermission(permission)
      .build()

  val participantAuthorizationAddedGen
      : Gen[v2.TopologyTransactionOuterClass.ParticipantAuthorizationAdded] =
    for {
      partyId <- Arbitrary.arbString.arbitrary
      participantId <- Arbitrary.arbString.arbitrary
      permission <- participantPermissionGen
    } yield v2.TopologyTransactionOuterClass.ParticipantAuthorizationAdded
      .newBuilder()
      .setPartyId(partyId)
      .setParticipantId(participantId)
      .setParticipantPermission(permission)
      .build()

  val participantAuthorizationRevokedGen
      : Gen[v2.TopologyTransactionOuterClass.ParticipantAuthorizationRevoked] =
    for {
      partyId <- Arbitrary.arbString.arbitrary
      participantId <- Arbitrary.arbString.arbitrary
    } yield v2.TopologyTransactionOuterClass.ParticipantAuthorizationRevoked
      .newBuilder()
      .setPartyId(partyId)
      .setParticipantId(participantId)
      .build()

  def transactionFilterGen: Gen[v2.TransactionFilterOuterClass.TransactionFilter] =
    for {
      filtersByParty <- Gen.mapOf(partyWithFiltersGen)
      filterForAnyPartyO <- Gen.option(filtersGen)
    } yield {

      filterForAnyPartyO match {
        case None =>
          v2.TransactionFilterOuterClass.TransactionFilter
            .newBuilder()
            .putAllFiltersByParty(filtersByParty.asJava)
            .build()
        case Some(filterForAnyParty) =>
          v2.TransactionFilterOuterClass.TransactionFilter
            .newBuilder()
            .putAllFiltersByParty(filtersByParty.asJava)
            .setFiltersForAnyParty(filterForAnyParty)
            .build()
      }
    }

  def partyWithFiltersGen: Gen[(String, v2.TransactionFilterOuterClass.Filters)] =
    for {
      party <- Arbitrary.arbString.arbitrary
      filters <- filtersGen
    } yield (party, filters)

  def filtersGen: Gen[v2.TransactionFilterOuterClass.Filters] =
    for {
      cumulatives <- cumulativeGen
    } yield v2.TransactionFilterOuterClass.Filters
      .newBuilder()
      .addAllCumulative(cumulatives.asJava)
      .build()

  def cumulativeGen: Gen[List[v2.TransactionFilterOuterClass.CumulativeFilter]] =
    for {
      templateIds <- Gen.listOf(identifierGen)
      interfaceFilters <- Gen.listOf(interfaceFilterGen)
      wildcardFilterO <- Gen.option(wildcardFilterGen)
    } yield {
      templateIds
        .map(templateId =>
          v2.TransactionFilterOuterClass.CumulativeFilter
            .newBuilder()
            .setTemplateFilter(
              v2.TransactionFilterOuterClass.TemplateFilter.newBuilder
                .setTemplateId(templateId)
                .build
            )
            .build()
        ) ++
        interfaceFilters
          .map(interfaceFilter =>
            v2.TransactionFilterOuterClass.CumulativeFilter
              .newBuilder()
              .setInterfaceFilter(interfaceFilter)
              .build()
          ) ++ (wildcardFilterO match {
          case Some(wildcardFilter) =>
            Seq(
              v2.TransactionFilterOuterClass.CumulativeFilter
                .newBuilder()
                .setWildcardFilter(wildcardFilter)
                .build()
            )
          case None => Seq.empty
        })
    }

  private[this] def interfaceFilterGen: Gen[v2.TransactionFilterOuterClass.InterfaceFilter] =
    Gen.zip(identifierGen, arbitrary[Boolean]).map { case (interfaceId, includeInterfaceView) =>
      v2.TransactionFilterOuterClass.InterfaceFilter
        .newBuilder()
        .setInterfaceId(interfaceId)
        .setIncludeInterfaceView(includeInterfaceView)
        .build()
    }

  private[this] def wildcardFilterGen: Gen[v2.TransactionFilterOuterClass.WildcardFilter] =
    arbitrary[Boolean].map { includeBlob =>
      v2.TransactionFilterOuterClass.WildcardFilter
        .newBuilder()
        .setIncludeCreatedEventBlob(includeBlob)
        .build()
    }

  def getActiveContractRequestGen: Gen[v2.StateServiceOuterClass.GetActiveContractsRequest] =
    for {
      transactionFilter <- transactionFilterGen
      verbose <- Arbitrary.arbBool.arbitrary
      activeAtOffset <- Arbitrary.arbLong.arbitrary
    } yield v2.StateServiceOuterClass.GetActiveContractsRequest
      .newBuilder()
      .setFilter(transactionFilter)
      .setVerbose(verbose)
      .setActiveAtOffset(activeAtOffset)
      .build()

  def activeContractGen: Gen[v2.StateServiceOuterClass.ActiveContract] =
    for {
      createdEvent <- createdEventGen
      synchronizerId <- Arbitrary.arbString.arbitrary
      reassignmentCounter <- Arbitrary.arbLong.arbitrary
    } yield v2.StateServiceOuterClass.ActiveContract
      .newBuilder()
      .setCreatedEvent(createdEvent)
      .setSynchronizerId(synchronizerId)
      .setReassignmentCounter(reassignmentCounter)
      .build()

  def unassignedEventGen: Gen[v2.ReassignmentOuterClass.UnassignedEvent] =
    for {
      unassignId <- Arbitrary.arbString.arbitrary
      contractId <- contractIdValueGen.map(_.getContractId)
      templateId <- identifierGen
      source <- Arbitrary.arbString.arbitrary
      target <- Arbitrary.arbString.arbitrary
      submitter <- Arbitrary.arbString.arbitrary
      reassignmentCounter <- Arbitrary.arbLong.arbitrary
      assignmentExclusivity <- instantGen
      witnessParties <- Gen.listOf(Arbitrary.arbString.arbitrary)
    } yield v2.ReassignmentOuterClass.UnassignedEvent
      .newBuilder()
      .setUnassignId(unassignId)
      .setContractId(contractId)
      .setTemplateId(templateId)
      .setSource(source)
      .setTarget(target)
      .setSubmitter(submitter)
      .setReassignmentCounter(reassignmentCounter)
      .setAssignmentExclusivity(Utils.instantToProto(assignmentExclusivity))
      .addAllWitnessParties(witnessParties.asJava)
      .build()

  def assignedEventGen: Gen[v2.ReassignmentOuterClass.AssignedEvent] =
    for {
      source <- Arbitrary.arbString.arbitrary
      target <- Arbitrary.arbString.arbitrary
      unassignId <- Arbitrary.arbString.arbitrary
      submitter <- Arbitrary.arbString.arbitrary
      reassignmentCounter <- Arbitrary.arbLong.arbitrary
      createdEvent <- createdEventGen
    } yield v2.ReassignmentOuterClass.AssignedEvent
      .newBuilder()
      .setSource(source)
      .setTarget(target)
      .setUnassignId(unassignId)
      .setSubmitter(submitter)
      .setReassignmentCounter(reassignmentCounter)
      .setCreatedEvent(createdEvent)
      .build()

  def incompleteUnassignedGen: Gen[v2.StateServiceOuterClass.IncompleteUnassigned] =
    for {
      createdEvent <- createdEventGen
      unassignedEvent <- unassignedEventGen
    } yield v2.StateServiceOuterClass.IncompleteUnassigned
      .newBuilder()
      .setCreatedEvent(createdEvent)
      .setUnassignedEvent(unassignedEvent)
      .build()

  def incompleteAssignedGen: Gen[v2.StateServiceOuterClass.IncompleteAssigned] =
    for {
      assignedEvent <- assignedEventGen
    } yield v2.StateServiceOuterClass.IncompleteAssigned
      .newBuilder()
      .setAssignedEvent(assignedEvent)
      .build()

  def contractEntryBuilderGen: Gen[
    v2.StateServiceOuterClass.GetActiveContractsResponse.Builder => v2.StateServiceOuterClass.GetActiveContractsResponse.Builder
  ] =
    Gen.oneOf(
      activeContractGen.map(e =>
        (b: v2.StateServiceOuterClass.GetActiveContractsResponse.Builder) => b.setActiveContract(e)
      ),
      incompleteUnassignedGen.map(e =>
        (b: v2.StateServiceOuterClass.GetActiveContractsResponse.Builder) =>
          b.setIncompleteUnassigned(e)
      ),
      incompleteAssignedGen.map(e =>
        (b: v2.StateServiceOuterClass.GetActiveContractsResponse.Builder) =>
          b.setIncompleteAssigned(e)
      ),
      Gen.const((b: v2.StateServiceOuterClass.GetActiveContractsResponse.Builder) => b),
    )

  def getActiveContractResponseGen: Gen[v2.StateServiceOuterClass.GetActiveContractsResponse] =
    for {
      workflowId <- Arbitrary.arbString.arbitrary
      entryGen <- contractEntryBuilderGen
    } yield v2.StateServiceOuterClass.GetActiveContractsResponse
      .newBuilder()
      .setWorkflowId(workflowId)
      .pipe(entryGen)
      .build()

  def getConnectedSynchronizersRequestGen
      : Gen[v2.StateServiceOuterClass.GetConnectedSynchronizersRequest] =
    for {
      party <- Arbitrary.arbString.arbitrary
    } yield v2.StateServiceOuterClass.GetConnectedSynchronizersRequest
      .newBuilder()
      .setParty(party)
      .build()

  def connectedSynchronizerGen
      : Gen[v2.StateServiceOuterClass.GetConnectedSynchronizersResponse.ConnectedSynchronizer] =
    for {
      synchronizerAlias <- Arbitrary.arbString.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      permission <- Gen.oneOf(
        v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_SUBMISSION,
        v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_CONFIRMATION,
        v2.StateServiceOuterClass.ParticipantPermission.PARTICIPANT_PERMISSION_OBSERVATION,
      )
    } yield v2.StateServiceOuterClass.GetConnectedSynchronizersResponse.ConnectedSynchronizer
      .newBuilder()
      .setSynchronizerAlias(synchronizerAlias)
      .setSynchronizerId(synchronizerId)
      .setPermission(permission)
      .build()

  def getConnectedSynchronizersResponseGen
      : Gen[v2.StateServiceOuterClass.GetConnectedSynchronizersResponse] =
    for {
      synchronizers <- Gen.listOf(connectedSynchronizerGen)
    } yield v2.StateServiceOuterClass.GetConnectedSynchronizersResponse
      .newBuilder()
      .addAllConnectedSynchronizers(synchronizers.asJava)
      .build()

  def getLedgerEndResponseGen: Gen[v2.StateServiceOuterClass.GetLedgerEndResponse] =
    for {
      offset <- Gen.option(Arbitrary.arbLong.arbitrary)
    } yield {
      val builder = v2.StateServiceOuterClass.GetLedgerEndResponse
        .newBuilder()
      offset.foreach(builder.setOffset)
      builder.build()
    }

  def getLatestPrunedOffsetsResponseGen
      : Gen[v2.StateServiceOuterClass.GetLatestPrunedOffsetsResponse] =
    for {
      participantPruned <- Gen.option(Arbitrary.arbLong.arbitrary)
      allDivulgedPruned <- Gen.option(Arbitrary.arbLong.arbitrary)
    } yield {
      val builder = v2.StateServiceOuterClass.GetLatestPrunedOffsetsResponse.newBuilder()
      participantPruned.foreach(builder.setParticipantPrunedUpToInclusive)
      allDivulgedPruned.foreach(builder.setAllDivulgedContractsPrunedUpToInclusive)
      builder.build()
    }

  def createdGen: Gen[v2.EventQueryServiceOuterClass.Created] =
    for {
      createdEvent <- createdEventGen
      synchronizerId <- Arbitrary.arbString.arbitrary
    } yield v2.EventQueryServiceOuterClass.Created
      .newBuilder()
      .setCreatedEvent(createdEvent)
      .setSynchronizerId(synchronizerId)
      .build()

  def archivedGen: Gen[v2.EventQueryServiceOuterClass.Archived] =
    for {
      archivedEvent <- archivedEventGen
      synchronizerId <- Arbitrary.arbString.arbitrary
    } yield v2.EventQueryServiceOuterClass.Archived
      .newBuilder()
      .setArchivedEvent(archivedEvent)
      .setSynchronizerId(synchronizerId)
      .build()

  def getEventsByContractIdResponseGen
      : Gen[v2.EventQueryServiceOuterClass.GetEventsByContractIdResponse] = {
    import v2.EventQueryServiceOuterClass.GetEventsByContractIdResponse as Response
    for {
      optCreated <- Gen.option(createdGen)
      optArchived <- Gen.option(archivedGen)
    } yield Response
      .newBuilder()
      .pipe(builder => optCreated.fold(builder)(c => builder.setCreated(c)))
      .pipe(builder => optArchived.fold(builder)(a => builder.setArchived(a)))
      .build()
  }

  def getPreferredPackagesRequestGen: Gen[
    v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackagesRequest
  ] = {
    import v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackagesRequest as Request
    for {
      packageVettingRequirements <- Gen.listOf(packageVettingRequirementsGen)
      synchronizerId <- Arbitrary.arbOption[String].arbitrary
      vettingValidAt <- protoTimestampGen
    } yield {
      val intermediate = Request
        .newBuilder()
        .setVettingValidAt(vettingValidAt)
      synchronizerId.foreach(intermediate.setSynchronizerId)
      packageVettingRequirements.foreach(intermediate.addPackageVettingRequirements)
      intermediate.build()
    }
  }

  def getPreferredPackageVersionRequestGen: Gen[
    v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackageVersionRequest
  ] = {
    import v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackageVersionRequest as Request
    for {
      packageNameGen <- packageNameGen
      synchronizerId <- Arbitrary.arbOption[String].arbitrary
      vettingValidAt <- protoTimestampGen
      parties <- Gen.listOf(Arbitrary.arbString.arbitrary)
    } yield {
      val intermediate = Request
        .newBuilder()
        .setPackageName(packageNameGen)
        .addAllParties(parties.asJava)
        .setVettingValidAt(vettingValidAt)
      synchronizerId.fold(intermediate)(intermediate.setSynchronizerId).build()
    }
  }

  def getPreferredPackageVersionResponseGen: Gen[
    v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackageVersionResponse
  ] = {
    import v2.interactive.InteractiveSubmissionServiceOuterClass.GetPreferredPackageVersionResponse as Response
    for {
      packagePreferenceO <- Gen.option(packagePreferenceGen)
    } yield {
      val builder = Response.newBuilder()
      packagePreferenceO.map(builder.setPackagePreference).getOrElse(builder).build()
    }
  }

  def completionStreamRequestGen
      : Gen[v2.CommandCompletionServiceOuterClass.CompletionStreamRequest] = {
    import v2.CommandCompletionServiceOuterClass.CompletionStreamRequest as Request
    for {
      userId <- Arbitrary.arbString.arbitrary
      parties <- Gen.listOf(Arbitrary.arbString.arbitrary)
      beginExclusive <- Gen.option(Arbitrary.arbLong.arbitrary)
    } yield {
      val builder = Request
        .newBuilder()
        .setUserId(userId)
        .addAllParties(parties.asJava)
      beginExclusive.foreach(builder.setBeginExclusive)
      builder.build()
    }
  }

  def completionGen: Gen[v2.CompletionOuterClass.Completion] = {
    import v2.CompletionOuterClass.Completion
    for {
      commandId <- Arbitrary.arbString.arbitrary
      status <- Gen.const(com.google.rpc.Status.getDefaultInstance)
      updateId <- Arbitrary.arbString.arbitrary
      userId <- Arbitrary.arbString.arbitrary
      actAs <- Gen.listOf(Arbitrary.arbString.arbitrary)
      submissionId <- Arbitrary.arbString.arbitrary
      deduplication <- Gen.oneOf(
        Arbitrary.arbLong.arbitrary.map(offset =>
          (b: Completion.Builder) => b.setDeduplicationOffset(offset)
        ),
        Arbitrary.arbLong.arbitrary.map(seconds =>
          (b: Completion.Builder) =>
            b.setDeduplicationDuration(Utils.durationToProto(Duration.ofSeconds(seconds)))
        ),
      )
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerTime <- synchronizerTimeGen
    } yield Completion
      .newBuilder()
      .setCommandId(commandId)
      .setStatus(status)
      .setUpdateId(updateId)
      .setUserId(userId)
      .addAllActAs(actAs.asJava)
      .setSubmissionId(submissionId)
      .pipe(deduplication)
      .setTraceContext(traceContext)
      .setOffset(offset)
      .setSynchronizerTime(synchronizerTime)
      .build()
  }

  def synchronizerTimeGen: Gen[v2.OffsetCheckpointOuterClass.SynchronizerTime] = {
    import v2.OffsetCheckpointOuterClass.SynchronizerTime
    for {
      synchronizerId <- Arbitrary.arbString.arbitrary
      recordTime <- instantGen
    } yield SynchronizerTime
      .newBuilder()
      .setSynchronizerId(synchronizerId)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def completionStreamResponseGen
      : Gen[v2.CommandCompletionServiceOuterClass.CompletionStreamResponse] = {
    import v2.CommandCompletionServiceOuterClass.CompletionStreamResponse as Response
    for {
      response <- Gen.oneOf(
        completionGen.map(completion => (b: Response.Builder) => b.setCompletion(completion)),
        offsetCheckpointGen.map(checkpoint =>
          (b: Response.Builder) => b.setOffsetCheckpoint(checkpoint)
        ),
      )
    } yield Response
      .newBuilder()
      .pipe(response)
      .build()
  }

  def transactionGen: Gen[v2.TransactionOuterClass.Transaction] = {
    import v2.TransactionOuterClass.Transaction
    for {
      updateId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      workflowId <- Arbitrary.arbString.arbitrary
      effectiveAt <- instantGen
      events <- Gen.listOf(eventGen)
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield Transaction
      .newBuilder()
      .setUpdateId(updateId)
      .setCommandId(commandId)
      .setWorkflowId(workflowId)
      .setEffectiveAt(Utils.instantToProto(effectiveAt))
      .addAllEvents(events.asJava)
      .setOffset(offset)
      .setSynchronizerId(synchronizerId)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  final case class Node(children: Seq[Node])

  def genNodeTree(maxDepth: Int, maxChildren: Int): Gen[Node] = {
    def generateChildren(currentDepth: Int): Gen[Node] =
      if (currentDepth == 0) {
        Gen.const(Node(List.empty))
      } else {
        for {
          numChildren <- Gen.choose(0, maxChildren)
          children <- Gen.listOfN(numChildren, generateChildren(currentDepth - 1))
        } yield Node(children)
      }

    generateChildren(maxDepth)
  }

  final case class NodeIds(id: Int, lastDescendant: Int)

  def assignIdsInPreOrder(node: Node): Seq[NodeIds] = {
    def getDescendants(currentNode: Node, currentId: Int): (Seq[NodeIds], Int) = {
      val (descendants, finalId) =
        currentNode.children.foldLeft((Seq.empty[NodeIds], currentId + 1)) {
          case ((acc, nextId), child) =>
            val (childDescendants, updatedId) = getDescendants(child, nextId)
            (acc ++ childDescendants, updatedId)
        }

      val lastDescendant = descendants.view.map(_.id).maxOption.getOrElse(currentId)

      (Seq(NodeIds(currentId, lastDescendant)) ++ descendants, finalId)
    }

    getDescendants(node, 0)._1
  }

  def transactionTreeGenWithIdsInPreOrder: Gen[v2.TransactionOuterClass.TransactionTree] = {
    import v2.TransactionOuterClass.{TransactionTree, TreeEvent}
    def treeEventGen(nodeId: Int, lastDescendantNodeId: Int): Gen[(Integer, TreeEvent)] =
      for {
        event <-
          if (lastDescendantNodeId == nodeId) // the node is a leaf node
            Gen.oneOf(
              createdEventGen(nodeId).map(e => (b: TreeEvent.Builder) => b.setCreated(e)),
              exercisedEventGen(nodeId, lastDescendantNodeId).map(e =>
                (b: TreeEvent.Builder) => b.setExercised(e)
              ),
            )
          else
            exercisedEventGen(nodeId, lastDescendantNodeId).map(e =>
              (b: TreeEvent.Builder) => b.setExercised(e)
            )
      } yield Int.box(nodeId) -> v2.TransactionOuterClass.TreeEvent
        .newBuilder()
        .pipe(event)
        .build()
    for {
      updateId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      workflowId <- Arbitrary.arbString.arbitrary
      effectiveAt <- instantGen
      nodeIds <- genNodeTree(maxDepth = 5, maxChildren = 5).map(assignIdsInPreOrder)
      multipleRoots <- Gen.oneOf(Gen.const(false), Gen.const(nodeIds.sizeIs > 1))
      nodeIdsFiltered = if (multipleRoots) nodeIds.filterNot(_.id == 0) else nodeIds
      eventsById <- Gen.sequence(nodeIdsFiltered.map { case NodeIds(start, end) =>
        treeEventGen(start, end)
      })
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield TransactionTree
      .newBuilder()
      .setUpdateId(updateId)
      .setCommandId(commandId)
      .setWorkflowId(workflowId)
      .setEffectiveAt(Utils.instantToProto(effectiveAt))
      .putAllEventsById(eventsById.asScala.toMap.asJava)
      .setOffset(offset)
      .setSynchronizerId(synchronizerId)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def transactionGenWithIdsInPreOrder: Gen[v2.TransactionOuterClass.Transaction] = {
    import v2.TransactionOuterClass.Transaction
    import v2.EventOuterClass.Event
    def eventGen(nodeId: Int, lastDescendantNodeId: Int): Gen[Event] =
      for {
        event <-
          if (lastDescendantNodeId == nodeId) // the node is a leaf node
            Gen.oneOf(
              createdEventGen(nodeId).map(e => (b: Event.Builder) => b.setCreated(e)),
              exercisedEventGen(nodeId, lastDescendantNodeId).map(e =>
                (b: Event.Builder) => b.setExercised(e)
              ),
            )
          else
            exercisedEventGen(nodeId, lastDescendantNodeId).map(e =>
              (b: Event.Builder) => b.setExercised(e)
            )
      } yield v2.EventOuterClass.Event
        .newBuilder()
        .pipe(event)
        .build()
    for {
      updateId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      workflowId <- Arbitrary.arbString.arbitrary
      effectiveAt <- instantGen
      nodeIds <- genNodeTree(maxDepth = 5, maxChildren = 5).map(assignIdsInPreOrder)
      multipleRoots <- Gen.oneOf(Gen.const(false), Gen.const(nodeIds.sizeIs > 1))
      nodeIdsFiltered = if (multipleRoots) nodeIds.filterNot(_.id == 0) else nodeIds
      events <- Gen.sequence(nodeIdsFiltered.map { case NodeIds(start, end) =>
        eventGen(start, end)
      })
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield Transaction
      .newBuilder()
      .setUpdateId(updateId)
      .setCommandId(commandId)
      .setWorkflowId(workflowId)
      .setEffectiveAt(Utils.instantToProto(effectiveAt))
      .addAllEvents(events)
      .setOffset(offset)
      .setSynchronizerId(synchronizerId)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def transactionTreeGen: Gen[v2.TransactionOuterClass.TransactionTree] = {
    import v2.TransactionOuterClass.{TransactionTree, TreeEvent}
    def idTreeEventPairGen =
      treeEventGen.map { e =>
        val id: Integer = e.getKindCase match {
          case TreeEvent.KindCase.CREATED => e.getCreated.getNodeId
          case TreeEvent.KindCase.EXERCISED => e.getExercised.getNodeId
          case TreeEvent.KindCase.KIND_NOT_SET => sys.error("unrecognized TreeEvent")
        }
        id -> e
      }
    for {
      updateId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      workflowId <- Arbitrary.arbString.arbitrary
      effectiveAt <- instantGen
      eventsById <- Gen.mapOfN(10, idTreeEventPairGen)
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield TransactionTree
      .newBuilder()
      .setUpdateId(updateId)
      .setCommandId(commandId)
      .setWorkflowId(workflowId)
      .setEffectiveAt(Utils.instantToProto(effectiveAt))
      .putAllEventsById(eventsById.asJava)
      .setOffset(offset)
      .setSynchronizerId(synchronizerId)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def topologyTransactionGen: Gen[v2.TopologyTransactionOuterClass.TopologyTransaction] = {
    import v2.TopologyTransactionOuterClass.TopologyTransaction
    for {
      updateId <- Arbitrary.arbString.arbitrary
      events <- Gen.listOf(topologyEventGen)
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerId <- Arbitrary.arbString.arbitrary
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield TopologyTransaction
      .newBuilder()
      .setUpdateId(updateId)
      .addAllEvents(events.asJava)
      .setOffset(offset)
      .setSynchronizerId(synchronizerId)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def reassignmentEventGen: Gen[v2.ReassignmentOuterClass.ReassignmentEvent] = {
    import v2.ReassignmentOuterClass.ReassignmentEvent
    for {
      event <- Gen.oneOf(
        unassignedEventGen.map(e => (b: ReassignmentEvent.Builder) => b.setUnassigned(e)),
        assignedEventGen.map(e => (b: ReassignmentEvent.Builder) => b.setAssigned(e)),
      )
    } yield ReassignmentEvent
      .newBuilder()
      .pipe(event)
      .build()
  }

  def reassignmentGen: Gen[v2.ReassignmentOuterClass.Reassignment] = {
    import v2.ReassignmentOuterClass.Reassignment
    for {
      updateId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      workflowId <- Arbitrary.arbString.arbitrary
      offset <- Arbitrary.arbLong.arbitrary
      events <- Gen.listOf(reassignmentEventGen)
      traceContext <- Gen.const(Utils.newProtoTraceContext("parent", "state"))
      recordTime <- instantGen
    } yield Reassignment
      .newBuilder()
      .setUpdateId(updateId)
      .setCommandId(commandId)
      .setWorkflowId(workflowId)
      .setOffset(offset)
      .addAllEvents(events.asJava)
      .setTraceContext(traceContext)
      .setRecordTime(Utils.instantToProto(recordTime))
      .build()
  }

  def getTransactionByOffsetRequestGen
      : Gen[v2.UpdateServiceOuterClass.GetTransactionByOffsetRequest] = {
    import v2.UpdateServiceOuterClass.GetTransactionByOffsetRequest as Request
    for {
      offset <- Arbitrary.arbLong.arbitrary
      requestingParties <- Gen
        .listOf(Arbitrary.arbString.arbitrary.suchThat(_.nonEmpty))
        .suchThat(_.nonEmpty)
    } yield Request
      .newBuilder()
      .setOffset(offset)
      .addAllRequestingParties(requestingParties.asJava)
      .build()
  }

  def getUpdateByOffsetRequestGen: Gen[v2.UpdateServiceOuterClass.GetUpdateByOffsetRequest] = {
    import v2.UpdateServiceOuterClass.GetUpdateByOffsetRequest as Request
    for {
      offset <- Arbitrary.arbLong.arbitrary
      updateFormat <- updateFormatGen
    } yield Request
      .newBuilder()
      .setOffset(offset)
      .setUpdateFormat(updateFormat)
      .build()
  }

  def getUpdateByIdRequestGen: Gen[v2.UpdateServiceOuterClass.GetUpdateByIdRequest] = {
    import v2.UpdateServiceOuterClass.GetUpdateByIdRequest as Request
    for {
      updateId <- Arbitrary.arbString.arbitrary.suchThat(_.nonEmpty)
      updateFormat <- updateFormatGen
    } yield Request
      .newBuilder()
      .setUpdateId(updateId)
      .setUpdateFormat(updateFormat)
      .build()
  }

  def getTransactionByIdRequestGen: Gen[v2.UpdateServiceOuterClass.GetTransactionByIdRequest] = {
    import v2.UpdateServiceOuterClass.GetTransactionByIdRequest as Request
    for {
      updateId <- Arbitrary.arbString.arbitrary.suchThat(_.nonEmpty)
      requestingParties <- Gen
        .listOf(Arbitrary.arbString.arbitrary.suchThat(_.nonEmpty))
        .suchThat(_.nonEmpty)
    } yield Request
      .newBuilder()
      .setUpdateId(updateId)
      .addAllRequestingParties(requestingParties.asJava)
      .build()
  }

  def getTransactionResponseGen: Gen[v2.UpdateServiceOuterClass.GetTransactionResponse] =
    transactionGen.map(
      v2.UpdateServiceOuterClass.GetTransactionResponse
        .newBuilder()
        .setTransaction(_)
        .build()
    )

  def getTransactionTreeResponseGen: Gen[v2.UpdateServiceOuterClass.GetTransactionTreeResponse] =
    transactionTreeGen.map(
      v2.UpdateServiceOuterClass.GetTransactionTreeResponse
        .newBuilder()
        .setTransaction(_)
        .build()
    )

  def getUpdatesRequestGen: Gen[v2.UpdateServiceOuterClass.GetUpdatesRequest] = {
    import v2.UpdateServiceOuterClass.GetUpdatesRequest as Request
    for {
      beginExclusive <- Arbitrary.arbLong.arbitrary
      endInclusiveO <- Gen.option(Arbitrary.arbLong.arbitrary)
      filter <- transactionFilterGen
      verbose <- Arbitrary.arbBool.arbitrary
    } yield {
      val partialBuilder = Request
        .newBuilder()
        .setBeginExclusive(beginExclusive)
        .setFilter(filter)
        .setVerbose(verbose)

      val builder =
        endInclusiveO.fold(partialBuilder)(partialBuilder.setEndInclusive)

      builder.build()
    }
  }

  def offsetCheckpointGen: Gen[v2.OffsetCheckpointOuterClass.OffsetCheckpoint] = {
    import v2.OffsetCheckpointOuterClass.OffsetCheckpoint
    for {
      offset <- Arbitrary.arbLong.arbitrary
      synchronizerTimes <- Gen.listOf(synchronizerTimeGen)
    } yield OffsetCheckpoint
      .newBuilder()
      .setOffset(offset)
      .addAllSynchronizerTimes(synchronizerTimes.asJava)
      .build()
  }

  def getUpdatesResponseGen: Gen[v2.UpdateServiceOuterClass.GetUpdatesResponse] = {
    import v2.UpdateServiceOuterClass.GetUpdatesResponse as Response
    for {
      update <- Gen.oneOf(
        transactionGen.map(transaction => (b: Response.Builder) => b.setTransaction(transaction)),
        reassignmentGen.map(reassingment =>
          (b: Response.Builder) => b.setReassignment(reassingment)
        ),
        offsetCheckpointGen.map(checkpoint =>
          (b: Response.Builder) => b.setOffsetCheckpoint(checkpoint)
        ),
        topologyTransactionGen.map(topologyTransaction =>
          (b: Response.Builder) => b.setTopologyTransaction(topologyTransaction)
        ),
      )
    } yield Response
      .newBuilder()
      .pipe(update)
      .build()
  }

  def getUpdateResponseGen: Gen[v2.UpdateServiceOuterClass.GetUpdateResponse] = {
    import v2.UpdateServiceOuterClass.GetUpdateResponse as Response
    for {
      update <- Gen.oneOf(
        transactionGen.map(transaction => (b: Response.Builder) => b.setTransaction(transaction)),
        reassignmentGen.map(reassingment =>
          (b: Response.Builder) => b.setReassignment(reassingment)
        ),
        topologyTransactionGen.map(topologyTransaction =>
          (b: Response.Builder) => b.setTopologyTransaction(topologyTransaction)
        ),
      )
    } yield Response
      .newBuilder()
      .pipe(update)
      .build()
  }

  def getUpdateTreesResponseGen: Gen[v2.UpdateServiceOuterClass.GetUpdateTreesResponse] = {
    import v2.UpdateServiceOuterClass.GetUpdateTreesResponse as Response
    for {
      update <- Gen.oneOf(
        transactionTreeGen.map(transactionTree =>
          (b: Response.Builder) => b.setTransactionTree(transactionTree)
        ),
        reassignmentGen.map(reassingment =>
          (b: Response.Builder) => b.setReassignment(reassingment)
        ),
        offsetCheckpointGen.map(checkpoint =>
          (b: Response.Builder) => b.setOffsetCheckpoint(checkpoint)
        ),
      )
    } yield Response
      .newBuilder()
      .pipe(update)
      .build()
  }

  val createCommandGen: Gen[v2.CommandsOuterClass.Command] =
    for {
      templateId <- identifierGen
      record <- recordGen
    } yield v2.CommandsOuterClass.Command
      .newBuilder()
      .setCreate(
        v2.CommandsOuterClass.CreateCommand
          .newBuilder()
          .setTemplateId(templateId)
          .setCreateArguments(record)
      )
      .build()

  val exerciseCommandGen: Gen[v2.CommandsOuterClass.Command] =
    for {
      templateId <- identifierGen
      choiceName <- Arbitrary.arbString.arbitrary
      value <- valueGen
    } yield v2.CommandsOuterClass.Command
      .newBuilder()
      .setExercise(
        v2.CommandsOuterClass.ExerciseCommand
          .newBuilder()
          .setTemplateId(templateId)
          .setChoice(choiceName)
          .setChoiceArgument(value)
      )
      .build()

  val createAndExerciseCommandGen: Gen[v2.CommandsOuterClass.Command] =
    for {
      templateId <- identifierGen
      record <- recordGen
      choiceName <- Arbitrary.arbString.arbitrary
      value <- valueGen
    } yield v2.CommandsOuterClass.Command
      .newBuilder()
      .setCreateAndExercise(
        v2.CommandsOuterClass.CreateAndExerciseCommand
          .newBuilder()
          .setTemplateId(templateId)
          .setCreateArguments(record)
          .setChoice(choiceName)
          .setChoiceArgument(value)
      )
      .build()

  val commandGen: Gen[v2.CommandsOuterClass.Command] =
    Gen.oneOf(createCommandGen, exerciseCommandGen, createAndExerciseCommandGen)

  val bytesGen: Gen[ByteString] =
    Gen
      .nonEmptyListOf(Arbitrary.arbByte.arbitrary)
      .map(x => ByteString.copyFrom(x.toArray))

  val disclosedContractGen: Gen[v2.CommandsOuterClass.DisclosedContract] = {
    import v2.CommandsOuterClass.DisclosedContract
    for {
      templateId <- identifierGen
      contractId <- Arbitrary.arbString.arbitrary
      createdEventBlob <- bytesGen
    } yield DisclosedContract
      .newBuilder()
      .setTemplateId(templateId)
      .setContractId(contractId)
      .setCreatedEventBlob(createdEventBlob)
      .build()
  }

  val commandsGen: Gen[v2.CommandsOuterClass.Commands] = {
    import v2.CommandsOuterClass.Commands
    for {
      workflowId <- Arbitrary.arbString.arbitrary
      userId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      commands <- Gen.listOf(commandGen)
      deduplication <- Gen.oneOf(
        Arbitrary.arbLong.arbitrary.map(duration =>
          (b: Commands.Builder) =>
            b.setDeduplicationDuration(Utils.durationToProto(Duration.ofSeconds(duration)))
        ),
        Arbitrary.arbLong.arbitrary.map(offset =>
          (b: Commands.Builder) => b.setDeduplicationOffset(offset)
        ),
      )
      minLedgerTimeAbs <- Arbitrary.arbInstant.arbitrary.map(Utils.instantToProto)
      minLedgerTimeRel <- Arbitrary.arbLong.arbitrary.map(t =>
        Utils.durationToProto(Duration.ofSeconds(t))
      )
      actAs <- Gen.nonEmptyListOf(Arbitrary.arbString.arbitrary)
      readAs <- Gen.listOf(Arbitrary.arbString.arbitrary)
      submissionId <- Arbitrary.arbString.arbitrary
      disclosedContract <- disclosedContractGen
      synchronizerId <- Arbitrary.arbString.arbitrary
    } yield Commands
      .newBuilder()
      .setWorkflowId(workflowId)
      .setUserId(userId)
      .setCommandId(commandId)
      .addAllCommands(commands.asJava)
      .pipe(deduplication)
      .setMinLedgerTimeAbs(minLedgerTimeAbs)
      .setMinLedgerTimeRel(minLedgerTimeRel)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .setSubmissionId(submissionId)
      .addDisclosedContracts(disclosedContract)
      .setSynchronizerId(synchronizerId)
      .build()
  }

  val unassignCommandGen: Gen[v2.ReassignmentCommandOuterClass.ReassignmentCommand] = {
    import v2.ReassignmentCommandOuterClass.{UnassignCommand, ReassignmentCommand}
    for {
      contractId <- Arbitrary.arbString.arbitrary
      source <- Arbitrary.arbString.arbitrary
      target <- Arbitrary.arbString.arbitrary
    } yield ReassignmentCommand
      .newBuilder()
      .setUnassignCommand(
        UnassignCommand
          .newBuilder()
          .setContractId(contractId)
          .setSource(source)
          .setTarget(target)
      )
      .build()
  }

  val assignCommandGen: Gen[v2.ReassignmentCommandOuterClass.ReassignmentCommand] = {
    import v2.ReassignmentCommandOuterClass.{AssignCommand, ReassignmentCommand}
    for {
      unassignId <- Arbitrary.arbString.arbitrary
      source <- Arbitrary.arbString.arbitrary
      target <- Arbitrary.arbString.arbitrary
    } yield ReassignmentCommand
      .newBuilder()
      .setAssignCommand(
        AssignCommand
          .newBuilder()
          .setUnassignId(unassignId)
          .setSource(source)
          .setTarget(target)
      )
      .build()
  }

  val reassignmentCommandGen: Gen[v2.ReassignmentCommandOuterClass.ReassignmentCommand] =
    Gen.oneOf(unassignCommandGen, assignCommandGen)

  val reassignmentCommandsGen: Gen[v2.ReassignmentCommandOuterClass.ReassignmentCommands] = {
    import v2.ReassignmentCommandOuterClass.ReassignmentCommands
    for {
      workflowId <- Arbitrary.arbString.arbitrary
      userId <- Arbitrary.arbString.arbitrary
      commandId <- Arbitrary.arbString.arbitrary
      submitter <- Arbitrary.arbString.arbitrary
      commands <- Gen.listOf(reassignmentCommandGen)
      submissionId <- Arbitrary.arbString.arbitrary
    } yield ReassignmentCommands
      .newBuilder()
      .setWorkflowId(workflowId)
      .setUserId(userId)
      .setCommandId(commandId)
      .setSubmitter(submitter)
      .addAllCommands(commands.asJava)
      .setSubmissionId(submissionId)
      .build()
  }

  def submitAndWaitResponseGen: Gen[v2.CommandServiceOuterClass.SubmitAndWaitResponse] = {
    import v2.CommandServiceOuterClass.SubmitAndWaitResponse as Response
    for {
      updateId <- Arbitrary.arbString.arbitrary
      completionOffset <- Arbitrary.arbLong.arbitrary
    } yield Response
      .newBuilder()
      .setUpdateId(updateId)
      .setCompletionOffset(completionOffset)
      .build()
  }
  def submitAndWaitForTransactionResponseGen
      : Gen[v2.CommandServiceOuterClass.SubmitAndWaitForTransactionResponse] = {
    import v2.CommandServiceOuterClass.SubmitAndWaitForTransactionResponse as Response
    for {
      transaction <- transactionGen
    } yield Response
      .newBuilder()
      .setTransaction(transaction)
      .build()
  }
  def submitAndWaitForReassignmentResponseGen
      : Gen[v2.CommandServiceOuterClass.SubmitAndWaitForReassignmentResponse] = {
    import v2.CommandServiceOuterClass.SubmitAndWaitForReassignmentResponse as Response
    for {
      reassignment <- reassignmentGen
    } yield Response
      .newBuilder()
      .setReassignment(reassignment)
      .build()
  }
  def submitAndWaitForTransactionTreeResponseGen
      : Gen[v2.CommandServiceOuterClass.SubmitAndWaitForTransactionTreeResponse] = {
    import v2.CommandServiceOuterClass.SubmitAndWaitForTransactionTreeResponse as Response
    for {
      transaction <- transactionTreeGen
    } yield Response
      .newBuilder()
      .setTransaction(transaction)
      .build()
  }

  val prefetchContractKeyGen: Gen[CommandsOuterClass.PrefetchContractKey] =
    for {
      templateId <- identifierGen
      contractKey <- valueGen
    } yield CommandsOuterClass.PrefetchContractKey
      .newBuilder()
      .setTemplateId(templateId)
      .setContractKey(contractKey)
      .build()

  val eventFormatGen: Gen[TransactionFilterOuterClass.EventFormat] =
    for {
      filtersByParty <- Gen.mapOf(partyWithFiltersGen)
      filterForAnyPartyO <- Gen.option(filtersGen)
      verbose <- Gen.oneOf(true, false)
    } yield {
      val builder = TransactionFilterOuterClass.EventFormat
        .newBuilder()
        .putAllFiltersByParty(filtersByParty.asJava)
        .setVerbose(verbose)

      filterForAnyPartyO match {
        case None =>
          builder.build()
        case Some(filterForAnyParty) =>
          builder
            .setFiltersForAnyParty(filterForAnyParty)
            .build()
      }
    }

  val transactionFormatGen: Gen[TransactionFilterOuterClass.TransactionFormat] =
    for {
      eventFormat <- eventFormatGen
      transactionShape <-
        Gen.oneOf(
          TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_ACS_DELTA,
          TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
        )
    } yield TransactionFilterOuterClass.TransactionFormat
      .newBuilder()
      .setEventFormat(eventFormat)
      .setTransactionShape(transactionShape)
      .build()

  val topologyFormatGen: Gen[TransactionFilterOuterClass.TopologyFormat] =
    for {
      partiesList <- Gen.listOf(Arbitrary.arbString.arbitrary)
      parties = partiesList.toSet
      partiesO <- Gen.option(parties)
      participantAuthorizationTopologyFormat =
        TransactionFilterOuterClass.ParticipantAuthorizationTopologyFormat
          .newBuilder()
          .addAllParties(partiesO.fold(List.empty[String].asJava)(_.toList.asJava))
          .build()
      participantAuthorizationTopologyFormatO <- Gen.option(participantAuthorizationTopologyFormat)
    } yield {
      val builder = TransactionFilterOuterClass.TopologyFormat.newBuilder()

      participantAuthorizationTopologyFormatO
        .map(builder.setIncludeParticipantAuthorizationEvents)

      builder.build()
    }

  val updateFormatGen: Gen[TransactionFilterOuterClass.UpdateFormat] =
    for {
      transactionFormatO <- Gen.option(transactionFormatGen)
      reassignmentFormatO <- Gen.option(eventFormatGen)
      topologyFormatO <- Gen.option(topologyFormatGen)
    } yield {
      val builder = TransactionFilterOuterClass.UpdateFormat
        .newBuilder()

      transactionFormatO.map(builder.setIncludeTransactions)
      reassignmentFormatO.map(builder.setIncludeReassignments)
      topologyFormatO.map(builder.setIncludeTopologyEvents)

      builder.build()
    }

}
