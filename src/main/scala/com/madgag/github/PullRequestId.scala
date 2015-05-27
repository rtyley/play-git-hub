/*
 * play-git-hub - a group of library code for Play, Git, and GitHub
 * Copyright (C) 2015 Roberto Tyley
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.madgag.github

object PullRequestId {
  def from(slug: String) = {
    val parts = slug.split('/')
    require(parts.length == 4)
    require(parts(2) == "pull")

    PullRequestId(RepoId(parts(0), parts(1)), parts(3).toInt)
  }
}


case class PullRequestId(repo: RepoId, num: Int) {
  lazy val slug = s"${repo.fullName}/pull/$num"
}
