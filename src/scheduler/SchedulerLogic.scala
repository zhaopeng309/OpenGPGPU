package scheduler

import chisel3._
import chisel3.util._

object SchedulerLogic {

  /**
   * Pure Scala Generator for GTO Arbiter
   * 
   * @param readyWarps Sequence of WarpContext (length 32)
   * @param lastWarpId The Warp ID that was scheduled in the previous cycle
   * @return A tuple of (Valid, WinnerWarpId)
   */
  def scheduleGTO(readyWarps: Seq[WarpContext], lastWarpId: UInt): (Bool, UInt) = {
    val numWarps = readyWarps.length
    
    // Step 1 & 2: Filter Valid & Ready and apply Type Filtering
    // We create masks for each type
    val isReady = readyWarps.map(w => w.valid && w.ready)
    val isTMA = readyWarps.map(w => w.instType === InstType.TMA)
    val isWGMMA = readyWarps.map(w => w.instType === InstType.WGMMA)
    
    val tmaReadyMask = isReady.zip(isTMA).map { case (r, t) => r && t }
    val wgmmaReadyMask = isReady.zip(isWGMMA).map { case (r, w) => r && w }
    val anyTmaReady = tmaReadyMask.reduce(_ || _)
    val anyWgmmaReady = wgmmaReadyMask.reduce(_ || _)
    
    // Select the actual candidate mask based on priority: TMA > WGMMA > ALU (others)
    val candidateMask = Seq.tabulate(numWarps) { i =>
      Mux(anyTmaReady, tmaReadyMask(i),
        Mux(anyWgmmaReady, wgmmaReadyMask(i), isReady(i)))
    }
    
    val anyCandidateReady = candidateMask.reduce(_ || _)

    // Step 3: GTO - Greedy then Oldest
    // Is the lastWarpId still a candidate?
    // We need to index candidateMask with lastWarpId. Since candidateMask is a Seq[Bool], we can use VecInit.
    val candidateVec = VecInit(candidateMask)
    val lastWarpReady = candidateVec(lastWarpId)
    
    // Find the Oldest among candidates
    // We compare ages. Non-candidates get age=0 so they won't be picked
    // if any candidate has age > 0. For tie-breaking (all ages == 0),
    // we use candidateMask to ensure a candidate is selected.
    val candidateAges = candidateMask.zip(readyWarps).zipWithIndex.map { case ((c, w), i) =>
      // If not a candidate, set age to 0 so it won't be picked unless all are 0
      val effectiveAge = Mux(c, w.ageCounter, 0.U(8.W))
      (i.U, effectiveAge, c)
    }
    
    // Comparator tree to find the max age among candidates
    // When ages are equal, prefer the candidate over non-candidate
    val oldest = candidateAges.reduce { (a, b) =>
      val (idA, ageA, cA) = a
      val (idB, ageB, cB) = b
      // Select based on: higher age wins; if equal age, candidate wins over non-candidate;
      // if both candidates with equal age, lower ID wins
      val pickA = Mux(ageA > ageB, true.B,
                  Mux(ageA < ageB, false.B,
                    Mux(cA && !cB, true.B,
                      Mux(!cA && cB, false.B,
                        idA <= idB))))
      (Mux(pickA, idA, idB), Mux(pickA, ageA, ageB), Mux(pickA, cA, cB))
    }
    
    val (oldestId, _, _) = oldest
    
    // GTO Decision: If last warp is a candidate, pick it (Greedy). Else pick Oldest.
    val winnerId = Mux(lastWarpReady, lastWarpId, oldestId)
    
    (anyCandidateReady, winnerId)
  }
}
