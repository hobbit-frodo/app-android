package org.coepi.android.repo

import io.reactivex.Completable
import io.reactivex.Single
import org.coepi.android.domain.model.Symptom
import org.coepi.android.system.log.log

interface SymptomRepo {
    fun symptoms(): Single<List<Symptom>>
    fun submitSymptoms(symptoms: List<Symptom>): Completable
}

class SymptomRepoImpl: SymptomRepo {

    override fun symptoms(): Single<List<Symptom>> = Single.just(listOf(
        Symptom("1", "Fever"),
        Symptom("2", "Tiredness"),
        Symptom("3", "Loss of appetite"),
        Symptom("4", "Muscle aches"),
        Symptom("5", "Trouble breathing"),
        Symptom("6", "Nasal congestion"),
        Symptom("7", "Sneezing"),
        Symptom("8", "Sore throat"),
        Symptom("9", "Headaches"),
        Symptom("10", "Diarrhea"),
        Symptom("11", "Loss of smell or taste")
    ))

    override fun submitSymptoms(symptoms: List<Symptom>): Completable =
        // Send to api
        Completable.complete().also {
            log.i("Submitted symptoms: $symptoms")
        }
}
