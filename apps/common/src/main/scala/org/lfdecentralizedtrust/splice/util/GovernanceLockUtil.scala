// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl

object GovernanceLockUtil {
  def foldSpecification[A](lock: gl.GovernanceLock)(
      onFull: gl.governancelockspecification.FullLockSpecification => A,
      onVesting: gl.governancelockspecification.VestingLockSpecification => A,
  ): A =
    lock.specification match {
      case s: gl.governancelockspecification.FullLockSpecification => onFull(s)
      case s: gl.governancelockspecification.VestingLockSpecification => onVesting(s)
      // TODO: better way to handle this? can't do an exhaustive match
      case s => sys.error(s"Unexpected GovernanceLockSpecification: $s")
    }

  def getGovernanceLockKind(lock: gl.GovernanceLock): gl.GovernanceLockKind =
    foldSpecification(lock)(_.kind, _.kind)

  def getVestingLockSpecification(
      lock: gl.GovernanceLock
  ): Option[gl.governancelockspecification.VestingLockSpecification] =
    foldSpecification(lock)(_ => None, Some(_))

  def getVestingLockTimes(lock: gl.GovernanceLock): Option[gl.VestingLockTimes] =
    getVestingLockSpecification(lock).map(_.times)
}
