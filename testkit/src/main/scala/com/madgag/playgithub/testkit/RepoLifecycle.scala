/*
 * Copyright 2023 Roberto Tyley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.madgag.playgithub.testkit

import akka.stream.Materializer
import com.madgag.github.Implicits.RichSource
import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.FR
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.{Org, Repo}

import scala.concurrent.{ExecutionContext, Future}

trait RepoLifecycle {
  def createRepo(cr: CreateRepo)(implicit github: GitHub, ec: ExecutionContext): FR[Repo]

  def listAllRepos()(implicit github: GitHub, ec: ExecutionContext, m: Materializer): Future[Seq[Repo]]
}

case object UserRepoLifecycle extends RepoLifecycle {
  def createRepo(cr: CreateRepo)(implicit github: GitHub, ec: ExecutionContext): FR[Repo] =
    github.createRepo(cr)

  def listAllRepos()(implicit github: GitHub, ec: ExecutionContext, m: Materializer): Future[Seq[Repo]] =
    github.listRepos("updated", "desc").all()
}

case class OrgRepoLifecycle(org: Org) extends RepoLifecycle {
  def createRepo(cr: CreateRepo)(implicit github: GitHub, ec: ExecutionContext): FR[Repo] =
    github.createOrgRepo(org.login, cr)

  def listAllRepos()(implicit github: GitHub, ec: ExecutionContext, m: Materializer): Future[Seq[Repo]] =
    github.listOrgRepos(org.login,"updated", "desc").all()
}