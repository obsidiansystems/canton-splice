// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.{
  BatchingConfig,
  CachingConfigs,
  LocalNodeParametersConfig,
  NonNegativeFiniteDuration,
  SessionSigningKeysConfig,
  WatchdogConfig,
}

final case class SpliceParametersConfig(
    batching: BatchingConfig = BatchingConfig(),
    caching: CachingConfigs = CachingConfigs(),
    customTimeouts: Map[String, NonNegativeFiniteDuration] = Map.empty,
) extends LocalNodeParametersConfig {
  override def alphaVersionSupport: Boolean = false

  override def watchdog: Option[WatchdogConfig] = None

  override def sessionSigningKeys: SessionSigningKeysConfig = ???
}
