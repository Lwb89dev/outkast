package net.primal.android.pow

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProofOfWorkViewModel @Inject constructor(proofOfWork: ProofOfWorkService) : ViewModel() {
    val tasks = proofOfWork.tasks
    val signerDroppedWork = proofOfWork.signerDroppedWork
}
